# 종목 수급 전체 백필

백업 후 `tb_stock_investor_daily`만 비우고 KIS에서 전체 재수집한다. 이 문서는 실행 가이드이며 코드 변경 과정에서는 운영 백필을 실행하지 않는다. 복구하지 못한 과거 행은 백업에만 남기고 서비스 DB에 복원하지 않는다. 기존 advisor 판단·픽·성과와 별도 시장별 수급은 보존한다.

## 일일 수급 확인 (읽기 전용)

운영 기본 설정은 평일 18:30 KST에 `DAILY`를 실행한다(`application.yml`의 prod `scheduler.stock-daily`). 실제 환경의 설정 변경 여부는 별도 확인한다. 일일 수급은 `DAILY.metadata_json.steps[]`의 `step=INVESTOR`에 기록된다. `INVESTOR_BACKFILL`은 과거 재수집 이력이므로 그 타입의 마지막 실행일만으로 일일 수집 중단을 판단하지 않는다.

`StockDailyPipelineJob`은 거래일에 `TargetResolver.activeTickers()`로 대상을 정하고 `StockInvestorCollectService.collectRecent()`를 호출한다. 휴장일에는 단계를 생략한다(force 실행 제외). `PipelineSteps`가 단계별 처리·실패 수를 기록한다. 단계 상태가 OK여도 종목 실패가 있을 수 있으므로 `failures`도 함께 확인한다. 실행 중이거나 휴장일인 run은 INVESTOR 기록이 아직 없거나 생략될 수 있다.

최근 DAILY 실행과 수급 단계:

```sql
SELECT r.run_id, r.target_date, r.status AS run_status,
       r.started_at AT TIME ZONE 'Asia/Seoul' AS started_kst,
       r.finished_at AT TIME ZONE 'Asia/Seoul' AS finished_kst,
       r.metadata_json->>'skipped' AS skip_reason,
       s.step->>'status' AS investor_status,
       (s.step->>'processed')::integer AS investor_processed,
       (s.step->>'failures')::integer AS investor_failures,
       r.error_message
FROM (
    SELECT * FROM tb_stock_collect_run
    WHERE job_type = 'DAILY'
    ORDER BY started_at DESC LIMIT 20
) r
LEFT JOIN LATERAL (
    SELECT step FROM jsonb_array_elements(r.metadata_json->'steps') AS step
    WHERE step->>'step' = 'INVESTOR'
) s ON true
ORDER BY r.started_at DESC;
```

실제 저장된 최신 거래일과 활성 종목별 지연도 함께 대조한다. 아래 ST는 기본 수집 그룹이며 실제 `kis.backfill.security-groups`와 맞춘다. 전체 MAX만으로 모든 종목의 수집 성공을 판정하지 않는다.

```sql
SELECT max(trade_date) AS latest_trade_date, count(*) AS rows
FROM tb_stock_investor_daily;

SELECT m.ticker, max(i.trade_date) AS latest_trade_date
FROM tb_stock_master m
LEFT JOIN tb_stock_investor_daily i ON i.ticker = m.ticker
WHERE m.is_active = true AND m.security_group IN ('ST')
GROUP BY m.ticker
ORDER BY latest_trade_date ASC NULLS FIRST, m.ticker;
```

거래 캘린더상 최근 완료 거래일과 비교한다. 운영 DB 조회 결과는 아직 확보되지 않았으므로 실제 일일 수집 성공 여부는 미확정이다. 별도 일일 수급 스케줄러를 추가할 필요는 없다.

## 1. 중지

수집·파생·advisor 정기 작업을 각 실제 `scheduler.stock-*`, `scheduler.advisor-*`의 `enabled=false`로 중지한다(와일드카드 설정은 지원하지 않음). MCP와 별도 MCP 인스턴스, Slack 조회(`ADVISOR_CHAT_ENABLED=false`)도 중지한다. 관리자 API는 유지한다. 실행 중 run은 `POST /api/stock/admin/collect/runs/{runId}/cancel`로 취소하고 실제 종료를 확인한다. 재시작한다면 이전 프로세스가 종료됐는지도 확인한다.

백필과 최종 파생 검증이 끝날 때까지 조회를 재개하지 않는다. 데이터 버전 필터를 대신하는 운영 조건이다.

## 2. 백업과 대상 확정

새 작업 디렉터리에서 실행한다. `$DATABASE_URL`은 비밀 설정으로 주입한 PostgreSQL 연결 문자열이다. 아래 파일을 이후 단계 내내 보존한다.

```bash
pg_dump "$DATABASE_URL" -Fc -f before-investor-backfill.dump
pg_restore --list before-investor-backfill.dump > backup-contents.txt
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "\copy (SELECT ticker, min(trade_date) AS start_date, max(trade_date) AS end_date, count(*) AS row_count FROM tb_stock_investor_daily GROUP BY ticker ORDER BY ticker) TO 'investor-before.csv' CSV HEADER"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "\copy (SELECT ticker, trade_date FROM tb_stock_investor_daily ORDER BY ticker, trade_date) TO 'investor-before-keys.csv' CSV HEADER"
```

백업의 복원 가능성을 별도 검증 DB에서 확인한 뒤 초기화한다. 활성 대상은 `TargetResolver`와 동일한 조건으로 추출한다. 아래 `ST`는 기본값이며 운영의 `kis.backfill.security-groups`가 다르면 실제 그룹 목록으로 바꾼다.

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "\copy (SELECT ticker FROM tb_stock_master WHERE is_active = true AND security_group IN ('ST') ORDER BY ticker) TO 'investor-active.csv' CSV HEADER"
```

대상은 기존 저장 종목과 현재 활성 대상의 합집합이다. 비활성·마스터 누락 종목도 포함한다. 작업 시작 시 최근 **완료 거래일**을 거래 캘린더로 확정해 종료일로 고정한다. 시작일은 운영 `kis.backfill.start-date`(기본 2015-01-01)와 기존 저장 최저일 중 이른 날짜다.

다음 코드는 API를 호출하지 않고 최대 5,000개씩 요청 파일을 생성한다. 환경변수 `INVESTOR_CONFIG_START`, `INVESTOR_END_DATE`에 확정한 날짜를 지정한다.

```bash
python3 - <<'PY'
import csv, json, os
from datetime import date
from pathlib import Path
with open('investor-before.csv') as f:
    before = list(csv.DictReader(f))
with open('investor-active.csv') as f:
    active = list(csv.DictReader(f))
tickers = sorted({r['ticker'] for r in before + active})
start = min([date.fromisoformat(os.environ['INVESTOR_CONFIG_START'])]
            + [date.fromisoformat(r['start_date']) for r in before])
end = date.fromisoformat(os.environ['INVESTOR_END_DATE'])
assert tickers and start <= end
out = Path('investor-requests')
out.mkdir(exist_ok=False)
for offset in range(0, len(tickers), 5000):
    body = dict(startDate=str(start), endDate=str(end),
                tickers=tickers[offset:offset+5000], resetCheckpoint=True)
    (out / f'{offset//5000+1:04}.json').write_text(json.dumps(body))
Path('investor-targets.json').write_text(json.dumps(dict(
    startDate=str(start), endDate=str(end), tickers=tickers)))
PY
```

## 3. 최초 초기화와 배포

**백업 확인 후 최초 한 번만 실행한다. 재개·재시도할 때 TRUNCATE하지 않는다.** 아래 SQL 경로는 저장소 루트 기준이다. 작업 디렉터리에서 실행한다면 절대 경로로 지정한다.

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c 'TRUNCATE TABLE tb_stock_investor_daily;'
cat src/main/resources/db/stock-derived-rebuild.sql \
    src/main/resources/db/migrate/20261014_01_investor_normalization.sql \
    src/main/resources/db/stock-derived.sql | psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -1
```

버전을 참조하는 기존 업종 MV를 먼저 제거해야 `DROP COLUMN IF EXISTS normalization_version`이 성공한다. 재구축 → 마이그레이션 → 새 파생 정의 순서를 지킨다. 마이그레이션은 금액의 NOT NULL·기본값과 버전 컬럼만 제거하며 기존 금액을 환산하지 않는다. 수정 앱을 정기 작업과 소비자가 중지된 설정으로 배포한다. `schema-postgres.sql`은 DROP을 포함한 초기화용이므로 운영 마이그레이션에 사용하지 않는다.

## 4. 표본 검증

실제 호출에서 `OPSQ2001 TIME LIMIT 00:00 ~ 15:40` 응답이 확인된 사례가 있다. **16시 이후(KST)에 아래 표본 요청의 성공과 저장값을 확인한 뒤 전체 실행**으로 진행한다. 이는 관측 사례에 따른 절차이며 공식 이용 시간 보장이 아니다. 같은 오류가 반복되면 전체 요청을 시작하지 않고 응답·호출 시각을 기록해 재확인한다.

기존 관리자 인증을 적용한다. `B`는 서버 URL을 포함한 `/api/stock/admin/collect`다.

```http
POST /api/stock/admin/collect/INVESTOR_BACKFILL
Content-Type: application/json

{"tickers":["005930"],"startDate":"2026-10-13","endDate":"2026-10-13","resetCheckpoint":true}
```

202 응답은 접수다. `GET $B/runs/{runId}`로 완료와 실패를 확인하고 저장값을 검증한다.

```sql
SELECT trade_date, foreign_net_amt, institution_net_amt, other_net_amt
FROM tb_stock_investor_daily WHERE ticker='005930' AND trade_date='2026-10-13';
-- 외국인 -1387781000000, 기관 239692000000, 기타 -68410000000 (원)
```

## 5. 전체 재수집과 재개

`BACKFILL_ALL`이 아닌 `INVESTOR_BACKFILL`에 준비한 각 요청 파일을 순차 제출한다. 한 요청의 실행 종료를 확인한 뒤 다음 파일로 진행한다.

```bash
curl --fail-with-body -X POST "$B/INVESTOR_BACKFILL" \
  -H 'Content-Type: application/json' --data-binary @investor-requests/0001.json
```

각 요청 본문과 runId를 함께 보존한다. run metadata의 tickers는 20개까지만 남아 원본 요청을 대체하지 못한다. `GET $B/checkpoints?jobType=INVESTOR_BACKFILL`과 `GET $B/checkpoints/summary?jobType=INVESTOR_BACKFILL`도 확인한다. 목록의 limit 때문에 전체가 보이지 않을 수 있으므로 누락 검증을 별도로 수행한다.

최초 전체 요청은 `resetCheckpoint=true`로 표본과 이전 체크포인트도 초기화한다. 중단·PAUSED 재개는 **동일 종목·시작일·종료일**에 `resetCheckpoint=false`로 제출한다. 재시도 한도에 도달한 종목은 해당 종목만 명시해 `resetCheckpoint=true`로 재시도한다. DONE/EXHAUSTED는 과거 키를 모두 복구했다는 보장이 아니다. 상장일 하한, KIS 소급 한계, 응답 공백은 백업과 대조한다. API가 기간 밖 행을 반환할 수 있으므로 변경 행 수만으로 성공을 판단하지 않는다.

## 6. 누락 검증

백업 전체 날짜 키를 임시 테이블에 읽어 재적재와 비교한다. 아래 전체 블록은 **같은 psql 세션**에서 실행한다.

```sql
CREATE TEMP TABLE investor_before_keys (ticker varchar(12), trade_date date, PRIMARY KEY (ticker, trade_date));
\copy investor_before_keys FROM 'investor-before-keys.csv' CSV HEADER
\copy (SELECT b.ticker, b.trade_date FROM investor_before_keys b LEFT JOIN tb_stock_investor_daily n USING (ticker, trade_date) WHERE n.ticker IS NULL ORDER BY b.ticker, b.trade_date) TO 'investor-missing-keys.csv' CSV HEADER
SELECT b.ticker, min(b.trade_date) AS missing_from, max(b.trade_date) AS missing_to, count(*) AS missing_rows
FROM investor_before_keys b LEFT JOIN tb_stock_investor_daily n USING (ticker, trade_date)
WHERE n.ticker IS NULL GROUP BY b.ticker ORDER BY b.ticker;
```

최저·최고일은 누락 범위의 외곽이다. 실제 공백은 `investor-missing-keys.csv`의 날짜별 키로 확인한다. 새 활성 종목처럼 백업 키가 없는 대상도 요청 목록·체크포인트·실제 저장 기간을 대조한다. 실패와 소급 한계는 종목·기간·행 수·runId·사유로 별도 결과에 기록한다. 복구 불가한 행은 백업에만 남기며 과거 금액을 추정 환산하거나 복원하지 않는다.

## 7. 전체 파생 갱신과 조회 재개

재수집 검증 후 실행하고 run 전체 완료를 확인한다.

```http
POST /api/stock/admin/collect/DERIVED_REFRESH
Content-Type: application/json

{"force":true}
```

```sql
SELECT trade_date, foreign_net_5d, institution_net_5d,
       round((foreign_net_5d/100000000.0)::numeric,1) AS foreign_eok,
       round((institution_net_5d/100000000.0)::numeric,1) AS institution_eok
FROM tb_stock_daily_metric WHERE ticker='005930' AND trade_date='2026-10-13';
SELECT sector_code, trade_date, foreign_net_sum, institution_net_sum
FROM mv_stock_sector_daily WHERE trade_date='2026-10-13';
```

파생 합계는 비결측 값의 부분 합계이며 전부 결측이면 NULL이다. 오래된 잘못된 금액이 정상값 또는 결측으로 바뀌었는지 확인한다. 유지보수 검증 경로에서 MCP `stockSeries(type=INVESTOR)`·`stockSnapshot`의 원 단위와 Slack의 억원 표시를 확인한 뒤 일반 조회·정기 작업을 재개한다.

최근 5거래일 원본 응답은 아직 확보되지 않았다. 누적 외국인 −28,798.31억원·기관 −15,469.66억원은 원본 확보 후 날짜별로 대조하며 현재 검증 완료값으로 취급하지 않는다. 복구 범위·누락 범위·백업 위치·요청 파일·runId·검증값을 기록한다. 코드 테스트 통과는 운영 복구 완료를 의미하지 않는다.

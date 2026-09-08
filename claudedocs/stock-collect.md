# 주식(KIS) 퀀트 데이터 수집 모듈 운영 가이드

작성일 2026-09-04. 브랜치 `feature/stock-collect` (Phase 0~4 구현 완료, 실전 키 검증 전).
승인 계획: `~/.claude/plans/moto-planner-agent-api-memoized-turtle.md`.

## 1. 한눈에 보기

- 원천: 한국투자증권(KIS) 실전 Open API + 종목 마스터 파일(`kospi_code/kosdaq_code/idxcode.mst.zip`).
- 저장: PostgreSQL. 시계열은 JdbcTemplate 배치 upsert(`ON CONFLICT … WHERE … IS DISTINCT FROM`), 마스터·run·체크포인트·토큰은 JPA.
- 원칙: **원주가 정본 + 수정계수 분리**(KIS 수정주가는 조회 시점 재계산이라 저장 금지), 상폐 종목 삭제 금지(비활성 보존), 실패는 예외가 아니라 run/체크포인트 상태.
- 코드 위치: `kr.hvy.blog.modules.stock` (client / domain / repository / application), 스케줄러는 `infra/scheduler/Stock*Scheduler`.
- 관리자 API: `/api/stock/admin/collect/**` (ROLE_ADMIN).

## 2. 배포 전 체크리스트 (Flyway 없음 — psql 수기 적용)

1. 운영 DB에 순서대로 적용한다. 모두 재실행 안전(`IF NOT EXISTS` / `ON CONFLICT DO NOTHING`).
   ```bash
   psql "$DATABASE_URL" -f src/main/resources/db/stock-schema.sql    # 테이블 21개 (재실행하면 새 테이블·COMMENT 만 반영)
   psql "$DATABASE_URL" -f src/main/resources/db/stock-derived.sql   # MV 4개 + 뷰 3개 (schema 뒤에). MV 정의를 바꿨으면 stock-derived-rebuild.sql 선행(§9)
   psql "$DATABASE_URL" -f src/main/resources/db/stock-seed.sql      # tb_stock_global_sector_map 시드
   ```
   `schema-postgres.sql`(전체 재구축용) 은 위 세 파일의 **원문**을 `-- >>> BEGIN db/stock-*.sql` / `-- <<< END …` 마커로 감싸 그대로 포함하고, DROP 블록에 stock 테이블 21개가 있다. 수정은 `db/stock-*.sql` 원본에만 하고 복사본을 갱신한다. `StockSchemaSyncTest` 가 불일치를 잡는다.
   **기존 테이블의 새 컬럼**은 `CREATE TABLE IF NOT EXISTS` 로 반영되지 않으므로 `src/main/resources/db/migrate/<날짜>_<번호>_<내용>.sql`(`ALTER TABLE … ADD COLUMN IF NOT EXISTS`, 재실행 안전)을 먼저 적용한다. 현재: `20260908_01_financial_ratio_columns.sql`(재무 9컬럼).
2. 환경변수 `KIS_APP_KEY`, `KIS_APP_SECRET` 를 주입한다(Dockerfile·yml 기본값 없음). 없으면 앱은 기동되지만 모든 수집 잡이 400으로 거부된다.
3. `scheduler.stock-*.enabled` 는 default/prod 모두 `false` 로 배포한다. 백필 완료 후 `true` 로 바꾼다.
4. 기동 후 확인: `GET /api/stock/admin/collect/token` → `POST /api/stock/admin/collect/token/refresh` (1분 1회 게이트, 재발급 실패 시 기존 토큰 유지).
5. 테이블이 없으면 JPA 엔티티 4개(`tb_stock_master`, `tb_stock_collect_run`, `tb_stock_collect_checkpoint`, `tb_stock_kis_token`)는 `ddl-auto=validate` 로 기동이 실패하고, JdbcTemplate 테이블은 런타임 오류가 난다.

## 3. 관리자 REST

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/stock/admin/collect/{jobType}` | 잡 실행. 장시간 잡(`longRunning`)은 `kisBackfillExecutor` 에 제출 후 **202**, 짧은 잡은 완료 후 200 |
| POST | `/api/stock/admin/collect/reload` | 지정 종목·기간 일봉 부분 재적재(`tickers` 필수, 체크포인트 RELOAD 네임스페이스) |
| GET | `/runs?jobType=&limit=` / `/runs/{runId}` | 실행 이력 (카운터·metadata_json·실패 표본) |
| POST | `/runs/{runId}/cancel` | 협조적 취소(종목 경계에서 3초 내 감지, 인터럽트 없음) |
| GET | `/checkpoints?jobType=&status=&limit=` / `/checkpoints/summary?jobType=` | 재개 지점·진행률 |
| GET/POST | `/token`, `/token/refresh` | 토큰 상태·강제 재발급 |

요청 본문 `BackfillRequest`(모두 선택): `startDate`, `endDate`, `tickerFrom`, `tickerTo`, `tickers[]`, `indexCodes[]`, `resetCheckpoint`, `force`.
응답 코드: 같은 잡이 RUNNING 이면 **409**(`runningRunId` 포함), 형식·전제조건 오류는 **400**. 둘 다 Slack 을 울리지 않는다.

잡 유형(`CollectJobType`): `BACKFILL_ALL`(아래 §4 단계 순차), `MASTER`, `HOLIDAY`, `INDEX_BACKFILL`, `PRICE_BACKFILL`, `STOCK_INFO`, `VALUATION`, `MARKET_STAT`, `CORP_ACTION`, `ADJUST_FACTOR`, `INVESTOR_BACKFILL`, `ETF_NAV_BACKFILL`, `MARKET_INVESTOR_BACKFILL`, `FINANCIAL_BACKFILL`, `OVERSEAS_BACKFILL`, `DERIVED_REFRESH`, `VALIDATE`, `DAILY`, `WEEKLY`, `OVERSEAS_DAILY`, `RELOAD`.

## 4. 최초 백필 순서 (장 마감 후 19:00 KST 이후 권장)

한 번에 돌리려면 `BACKFILL_ALL` 하나면 된다. 아래 16단계를 이 순서로 하위 run 으로 실행하며(상위 run 의 `metadata_json.steps[]` 에 단계별 runId·상태·행 수),
한 단계가 실패해도 다음으로 넘어가고 재트리거하면 체크포인트부터 이어받는다. 상위 run 을 cancel 하면 진행 중인 하위 잡이 종목 경계에서 멈춘다.

```bash
B=https://<host>/api/stock/admin/collect; H='Content-Type: application/json'
curl -X POST $B/BACKFILL_ALL                             # 전 종목 2015~ 전체 (202, 4~5시간). 요청 본문은 하위 잡에 그대로 전달
curl -X POST $B/BACKFILL_ALL -H "$H" -d '{"tickerFrom":"000000","tickerTo":"099999"}'   # 종목 범위로 나눠 돌릴 때
```

단계별로 따로 돌릴 때:

```bash
curl -X POST $B/MASTER                                   # 마스터 파일 3종 → tb_stock_master·SCD2·섹터맵·지수마스터 (동기)
curl -X POST $B/HOLIDAY -H "$H" -d '{"startDate":"2026-01-01"}'   # startDate 지정 시 최대 15페이지(≈1년)
curl -X POST $B/INDEX_BACKFILL                           # 지수 마스터 전체 × 10년 (202)
curl -X POST $B/PRICE_BACKFILL -H "$H" -d '{"tickerFrom":"000000","tickerTo":"099999"}'   # 900종목씩 3일 밤 분할 권장
curl -X POST $B/STOCK_INFO                               # 상장일·상폐일 보강
curl -X POST $B/CORP_ACTION                              # 예탁원 7종 2015~ (전 종목 기간 조회라 호출 수 적음)
curl -X POST $B/ADJUST_FACTOR                            # 계수 산출 → MV → KIS 수정주가 표본 20종목 대조
curl -X POST $B/INVESTOR_BACKFILL                        # 소급 깊이 실측 (EXHAUSTED 분포 확인)
curl -X POST $B/VALUATION                                # 오늘 스냅샷(시총·PER·PBR). 당일만 제공되므로 DAILY 로 누적 (BACKFILL_ALL 은 날짜 필터를 떼고 넘김)
curl -X POST $B/MARKET_STAT                              # 공매도·신용·프로그램 최근 window-days
curl -X POST $B/MARKET_INVESTOR_BACKFILL                 # 시장별(KOSPI·KOSDAQ) 투자자 15주체 2015~ (202, 영업일 역순 ≈5,800호출 ≈ 6분). KisMarketInvestorManualTest 실측 선행
curl -X POST $B/ETF_NAV_BACKFILL                         # 활성 ETF(EF) NAV·괴리율 2015~ (202, ≈1,000종목 × 29윈도우 ≈ 30분)
curl -X POST $B/FINANCIAL_BACKFILL                       # 종목당 12호출(6종 × 연/분기), 전 종목 ≈36분
curl -X POST $B/OVERSEAS_BACKFILL                        # kis.overseas.symbols
curl -X POST $B/DERIVED_REFRESH                          # MV 4개 갱신 (소요 시간 3분 룰 실측)
curl -X POST $B/VALIDATE                                 # 정합성 점검
```

- 진행 확인: `GET $B/checkpoints/summary?jobType=PRICE_BACKFILL` (DONE ≥ 99% 목표, `EXHAUSTED` = KIS 소급 한계, `PAUSED` = 윈도우 상한(`kis.backfill.max-windows`) 도달·재트리거로 이어감, `FAILED` = 5회 초과).
- 백필 실행기는 단일 스레드(큐 10). 다른 백필 잡을 넣으면 순서대로 돈다. 같은 잡은 409.
- 종목 동시성 `kis.backfill.concurrency`(3), 전역 한도 `kis.rate-limit`(200ms×3 = 15건/초, EGW00201 시 자동 하향·연속 성공 200회 후 원복).

## 5. 스케줄 (KST, `@Profile("!default")`, yml `enabled` 로 개별 on/off)

| 스케줄러 | cron | 잡 | lockAtMostFor |
|---|---|---|---|
| `StockMasterScheduler` | 평일 05:30 | MASTER(마스터 3종 + theme_code.mst → KRX·THEME 섹터맵, 테마 실패 시 THEME 만 건너뜀 `themeError`) → HOLIDAY(1페이지) | 15m |
| `StockDailyCollectScheduler` | 평일 18:30 | DAILY: INDEX → PRICE → VALUATION → INVESTOR → MARKET_INVESTOR(시장별 오늘 1회, +2호출) → ETF_NAV(활성 ETF 최근 1윈도우, +≈1,000호출) → STATS(`kis.stats.enabled`, 기본 **true**, 종목당 3호출 ≈ 9.5분) → CA_HINT → VALIDATE → DERIVED (총 ≈19분) | 40m |
| `StockOverseasScheduler` | 화~토 06:30 | OVERSEAS_DAILY | 15m |
| `StockWeeklyScheduler` | 일 03:00 | WEEKLY: CORP_ACTION(±3개월) → STOCK_INFO(기업행사에만 있는 종목을 조회해 상폐일 있는 것만 비활성 마스터 행으로, 메타 `stockInfoCandidates`/`stockInfoApplied`) → ADJUST_FACTOR → FINANCIAL(정정 감지) | 2h |

- DAILY 는 휴장일이면 run 만 남기고 끝난다(`force:true` 로 무시). PRICE 단계 실패 시 DERIVED 만 건너뛴다. 단계별 상태·소요는 run `metadata_json.steps[]`.
- `/admin` 스케줄러 카탈로그(`SchedulerCatalog`)에 4개가 등록되어 있다.
- 로그 정리(`LogCleanerScheduler`): `tb_stock_kis_api_failure` 90일, 종료된 `tb_stock_collect_run` 1년.

## 6. 데이터 모델

| 테이블 | 키 | 내용 |
|---|---|---|
| `tb_stock_master` / `tb_stock_master_history` | ticker / (ticker, valid_from) | 현재 상태 / SCD2(`snapshot_hash`, 구간 [valid_from, valid_to)) — 이력 시작 = 구축일 |
| `tb_stock_index_master`, `tb_stock_index_daily` | index_code / (code, date) | 업종코드 마스터(idxcode.mst), 지수 일봉. `0001` 날짜 집합 = 영업일 정본 |
| `tb_stock_market_holiday` | trade_date | 미래 개장일 판정 |
| `tb_stock_daily_price` | (ticker, date) | **원주가** OHLCV + 락구분·분할비율·mod_yn 힌트, 등락률은 전일대비/전일종가로 계산 |
| `tb_stock_valuation_daily` | (ticker, date) | 시총(원, `hts_avls`×1e8)·PER·PBR·EPS·BPS·52주·외인소진율 — 당일만 제공, 매일 누적 |
| `tb_stock_investor_daily` | (ticker, date) | 외국인·기관·개인·연기금(기금)·기타 순매수 금액/수량 (FHPTJ04160001) |
| `tb_stock_corporate_action` | uk(ticker, date, type, source) | 예탁원 7종 + 일봉 힌트(CHART_HINT), `raw_json` |
| `tb_stock_adjust_event` | (ticker, date, type) | price/volume factor, `verified` |
| `tb_stock_financial` | (ticker, period, type, revision_seq) | 손익·대차·재무비율·성장성·수익성·안정성 6종 병합(지표 19컬럼), `available_from`(LAG_45D/90D), `first_seen_at`. 확장 9컬럼이 NULL 인 기존 행에 처음 값이 오면 리비전 없이 채움 |
| `tb_stock_sector_map`, `tb_stock_global_sector_map` | | KRX 중분류 매핑(SCD, 종목당 1개) + THEME 테마 매핑(theme_code.mst, N:M, 테마명은 `sector_name`, 목록은 `SELECT DISTINCT sector_code, sector_name WHERE source='THEME' AND valid_to IS NULL`) / 국내 섹터↔미국 참조 시드 |
| `tb_stock_global_market_daily` | (symbol, date) | 해외 지수·환율(N/X)·ETF·개별주(EQ, 수정주가) |
| `tb_stock_market_stat_daily` | (ticker, date) | 공매도·신용잔고·프로그램(출처별 부분 upsert) |
| `tb_stock_etf_nav_daily` | (ticker, date) | ETF 종가·NAV·괴리율 (FHPST02440200, 활성 EF 만, ETN 제외) |
| `tb_stock_market_investor_daily` | (market_type, date) | KOSPI/KOSDAQ 투자자 15주체 순매수 대금·수량 (FHPTJ04040000, 영업일 역순 백필) |
| `tb_stock_collect_run`, `tb_stock_collect_checkpoint`, `tb_stock_kis_token`, `tb_stock_kis_api_failure` | | 운영 |

파생(`stock-derived.sql`): `mv_stock_adjust_factor`(EXP(SUM(LN)) 누적 계수, 이벤트 보유 종목만) → `vw_stock_daily_price_adj`(**가격 소비자의 유일한 진입점**) → `mv_stock_daily_metric`(ret 1/5/20/60/120, MA 5/20/60/120, 이격도, 52주 고점 252행, 거래대금 5/60, 외인·기관 5일) → `mv_stock_index_metric` → `mv_stock_sector_daily`(현재 KRX 매핑으로 과거를 근사) / `vw_stock_universe_daily`(ST·활성·비정지·비관리·시총 1,000억·거래대금 5일 10억 하한) / `vw_stock_market_calendar`.
REFRESH 는 `CONCURRENTLY`(유니크 인덱스 필수) 이며 순서는 `DerivedMetricRefreshService.REFRESH_ORDER`.

이름 규약: 테이블·뷰·MV 는 전부 `tb_stock_` / `vw_stock_` / `mv_stock_` 접두사다(`\dt tb_stock_*` 로 한 번에 본다). JPA 엔티티는 `HvyPhysicalNamingStrategy` 가 클래스명에 `tb_` 를 무조건 붙이므로 `@Table` 없이 클래스명(`StockKisToken` → `tb_stock_kis_token`)으로 맞춘다.
enum 규약: stock 모듈의 public enum 은 모두 `EnumCode<String>`(hvy-common) 을 구현하고 **code == 상수명**이다(부분 인덱스 `status='RUNNING'`·체크포인트 키·REST 경로 변수가 상수명 기준). 엔티티 필드는 `domain/code/converter/*Converter`(`@Convert`), JdbcTemplate 라이터는 `getCode()` / `EnumCodes.fromCode()` 로 읽고 쓴다. `StockEnumCodeTest` 가 불변식과 누락을 잡는다.

## 7. 수정주가 계수 규칙 (`AdjustFactorCalculator`)

효력일 **이전** 거래일 가격 × price_factor, 거래량 × volume_factor.

| 행사 | 효력일 | price_factor | volume_factor |
|---|---|---|---|
| 액면분할·병합 | 변경상장일(list_dt) → 기준일 | 변경후/변경전 액면가 | 역수 |
| 무상증자 r(주당) | 권리락일 | 1/(1+r) | 1+r |
| 유상증자 r, 발행가 P, 전일종가 C | 권리락일 | (C+P·r)/((1+r)·C) | 1+r |
| 감자 r | 변경상장일 | r<1: 1/r, r>1: r | 반대 |
| 합병/분할·배당·상장 | — | 원본만 보관 (수기 판단) | — |

검증: `ADJUST_FACTOR` 잡이 이벤트 보유 종목 20개(또는 `tickers`)를 KIS 수정주가(`FID_ORG_ADJ_PRC=0`) 최근 3윈도우와 대조해 상대오차 ≤ 0.5% 면 `verified=true`. 거래량 오차는 run 메타데이터(`verification`)에 남는다 → **KIS 가 거래량도 보정하는지의 실측값**.

효력일 상한(2026-09-08): 예탁원 일정은 오늘 +90일까지 들어오므로 `mv_stock_adjust_factor` 는 **효력일 ≤ 오늘(KST)** 인 이벤트만 곱한다. DAILY 가 매일 REFRESH 하므로 효력일 당일 자동 반영된다. 비율이 사전에 확정되는 분할·병합·무상증자·감자는 미리 계수를 만들어 두고, **유상증자만 권리락 전일 종가가 필요해 효력일이 지난 뒤(WEEKLY ADJUST_FACTOR)에 산출**한다 → 유상증자는 효력일~다음 일요일 최대 6일 미반영(run 메타 `eventsDeferred`).

## 8. Slack 임계 (`CollectNotifier`)

- 종목 단위 실패율 < 1%: run `PARTIAL` 만 기록 / 1~5%: `#hvy-notify` / ≥ 5% 또는 잡 자체 실패: `#hvy-error` + 멘션. 메시지에는 대표 오류 3건과 EGW00201 횟수만.
- 정합성(`CollectValidationService`): 활성 종목 대비 당일 일봉 행 수 차이 > 2% → `#hvy-error`, 결측·이상치(|등락률|>30% 이고 ±3일 기업행사 없음)·OHLC 위반·52주 편차(>1%, 계수 없는 종목) → `#hvy-notify`.

## 9. 복구·재적재

- 프로세스 강제 종료·재배포: 기동 시 RUNNING run 을 **전부** FAILED 로 정리한다(`kis.run.reconcile-all-on-startup=true`, 단일 인스턴스 전제). 다중 인스턴스로 가면 false 로 두고 `stale-after`(6h) 기준만 쓴다. 체크포인트 `IN_PROGRESS` 는 다음 트리거가 커서부터 이어받는다.
- 특정 종목 다시 받기: `POST /reload {"tickers":["005930"],"startDate":"2024-01-01"}` (삭제 없이 upsert 덮어쓰기).
- 체크포인트 FAILED 5회 초과 → 건너뜀. 되돌리려면 `{"tickers":[…],"resetCheckpoint":true}`.
- 윈도우 상한(`max-windows`) 도달은 FAILED 가 아니라 **PAUSED** 로 남고 attempt 를 소모하지 않는다. 같은 잡을 다시 트리거하면 커서부터 이어받는다. (2026-09-08 이전 상한 40 으로 FAILED 가 된 INVESTOR_BACKFILL 2,185건도 attempt 1 이라 `POST /INVESTOR_BACKFILL` 재트리거만으로 이어간다.)
- KIS 장애로 하루 결손: 다음 날 DAILY 가 최근 100건 윈도우를 재수집하므로 자동 복구. 2일 이상은 reload.
- MV 미적용 상태(psql 전): `ADJUST_FACTOR`·`DERIVED_REFRESH` 는 경고만 남기고 건너뛴다.
- MV 정의를 바꿨을 때(파생 재구축): `CREATE MATERIALIZED VIEW IF NOT EXISTS` 는 기존 MV 를 바꾸지 못하므로 의존 역순 DROP 후 재생성한다. 한 트랜잭션이라 소비자가 뷰 부재를 보지 않지만 수 분 락이 걸리므로 DAILY 18:30 창 밖에서 실행한다.
  ```bash
  cat src/main/resources/db/stock-derived-rebuild.sql src/main/resources/db/stock-derived.sql | psql -1 "$DATABASE_URL"
  curl -X POST $B/ADJUST_FACTOR    # 계수 재산출·대조. 최신 거래일 adj_close == raw_close 확인
  ```
- 재무 확장 9컬럼(2026-09-08)은 첫 재조회에서 기존 최신 행에 **채워지고 리비전을 올리지 않는다**(핵심 10개가 같고 확장이 전부 NULL 일 때). 채우려면 `POST /FINANCIAL_BACKFILL -d '{"resetCheckpoint":true}'` 또는 다음 WEEKLY.
- 2026-09-08 효력일 상한 도입 이전에 적재된 **미래 유상증자 계수 행**은 효력일에 잘못된 계수(최근 종가 기준)로 켜질 수 있어 1회 수기 삭제한다(다른 유형의 미래 행은 MV 술어로 무해): `DELETE FROM tb_stock_adjust_event WHERE action_type='RIGHTS_ISSUE' AND effective_date > (now() AT TIME ZONE 'Asia/Seoul')::date`.

## 10. 실측이 필요한 항목 (실전 키 필요, 코드가 가정한 값)

| 항목 | 가정 | 확인 방법 |
|---|---|---|
| 일봉 소급 한계 | **확인됨(2026-09-08)** — 2,720종목 전부 DONE·EXHAUSTED 0, 최소 2014-10-27 (2015 이전까지 제공) | `PRICE_BACKFILL` 체크포인트 summary |
| 상폐 종목 조회 가능 여부 | **확인됨(2026-09-07)** — 동양생명 082640 에 `lstg_abol_dt=20260831` 이 옴. 단, 응답 `pdno` 는 12자 상품번호(`00000A082640`)라 ticker 로 쓰면 varchar(10) 초과 → 요청 종목코드를 키로 쓰도록 수정. `pdno` 가 요청 코드로 끝나지 않으면 run metadata `pdnoMismatch` 에 센다 | `POST /STOCK_INFO {"tickers":["<상폐코드>"]}` → `is_active=false` 행이 6자 ticker 로 생성되는지 |
| 수정주가 모드 거래량 보정 | 미정 | `ADJUST_FACTOR` run metadata `verification` 의 volume 오차 |
| 투자자 일별 소급 깊이·페이징 | 호출당 30건(일부 60건) 확인. 2026-09-08 실측은 API 한계가 아니라 `max-windows=40` 상한(2021-10-13/14·2016-11-22/23 두 클러스터)에서 멈춘 것 → 120 으로 올려 재실행 | 재실행 후 `EXHAUSTED` 의 `earliest_loaded` 분포 (PAUSED 가 남으면 재트리거) |
| 투자자·재무 금액 단위 | 응답 그대로(원 / 억원 추정) | 삼성전자 1건을 HTS 값과 비교 |
| 시가총액 `hts_avls` | 억원 → ×1e8 | `tb_stock_valuation_daily.market_cap` 대조 |
| 마스터 `lstn_stcn` | 헤더 주석은 "(천)" 이나 원값 저장 | 현재가 API `lstn_stcn` 과 비교 후 필요 시 `StockMaster.applyFrom` 보정 |
| 예탁원 배정율 단위 | 주당 주수(0.5 = 1주당 0.5주) | 무상증자 종목 계수 대조 결과 |
| ksdinfo 연속조회 | CTS 승계 없이 tr_cont=N, 동일 페이지 반복 시 중단 | run 실패 목록 |
| 해외 코드 `FX@KRW`, `SOX`, AMS 거래소 ETF | **확인됨(2026-09-08)** — yml 29심볼 전부 적재(82,805행, 2014-09~) | 심볼별 행 수가 극단적으로 적은 것만 재확인 |
| 재무 API 파라미터 대소문자 | **확인됨(2026-09-08)** — 손익·대차·재무비율 3종 116K행·2,565종목·2004~ 적재 | — |
| 재무 성장성·수익성·안정성 3종 필드명 | data.csv column_mapping 그대로(`bsop_prfi_inrt`, `cptl_ntin_rate`, `crnt_rate` 등) | `FINANCIAL_BACKFILL {"tickers":["005930"],"resetCheckpoint":true}` 후 `raw_json` 키·9컬럼 값 확인 |
| ETF NAV (FHPST02440200) | output 단일 리스트, 100건/호출, 괴리율 부호·NAV 소수 4자리 가정 | `KisEtfNavManualTest`(069500) → `ETF_NAV_BACKFILL` 체크포인트 EXHAUSTED 분포 |
| 시장별 투자자 (FHPTJ04040000) | 경로 `inquire-investor-daily-by-market`(예제 디렉터리명 추정), `FID_INPUT_ISCD`=종합지수코드·`_1`=KSP/KSQ·`_2`=종합지수코드, 호출당 1일, 금액 원 | **백필 전 필수** `KisMarketInvestorManualTest`: KSP 와 KSQ 응답이 다른지, 0001 vs 1001, 응답 일수, HTS 0404 단위 대조 |
| theme_code.mst 레이아웃 | 앞 3자 테마코드 + 가변 테마명 + 줄 끝 10자 종목코드(6자 또는 A 접두) | `KisThemeFileManualTest`(@Disabled 해제, 키 불필요) 히스토그램 → `THEME_TICKER_WIDTH`·`ThemeCodeRecord.ticker()` 확정 |
| EGW00133 등 토큰 오류 코드 | `KisErrorCode` 추정값 | `tb_stock_kis_api_failure.kis_msg_cd` |

## 11. 테스트

```bash
# colima 소켓 (testcontainers-colima-socket 메모)
export DOCKER_HOST=unix:///Users/nwkim/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew test --tests "kr.hvy.blog.modules.stock.*"          # 단위 + PG 18 컨테이너(DDL·MV·upsert)
KIS_APP_KEY=… KIS_APP_SECRET=… ./gradlew test --tests "kr.hvy.blog.modules.stock.client.KisDailyChartManualTest"   # 실전 스모크
```

H2 로는 `ON CONFLICT`·부분 유니크·MV 가 검증되지 않으므로 PG 컨테이너 테스트(`*PgTest`)가 스키마 변경의 안전망이다.

## 12. 승인 계획 대비 변경점

- 관리자 경로 `/api/stock/admin/collect` (SecurityConfig `/api/*/admin/**` 는 단일 세그먼트).
- 마스터 고정폭은 공식 C 헤더 기준 KOSPI 227자 70필드 / KOSDAQ 221자 64필드(계획의 65/62 는 근사치).
- 예탁원 `stock_split` API 는 없고 `rev_split`(액면교체)이 분할·병합을 모두 담는다.
- 투자자 일별은 FHPTJ04160001 하나로 백필·증분을 모두 처리(FHKST01010900 미사용).
- 재무는 6종(손익·대차·재무비율·성장성·수익성·안정성)을 호출한다(2026-09-08 확장, 종목당 12호출). 동명 필드 `grs`·`lblt_rate` 는 전문 API(성장성·안정성) 값. `profit_growth` 는 순이익 증가율(ntin_inrt)이고 영업이익 증가율은 `operating_profit_growth`. 기존 행의 새 컬럼은 첫 재조회에서 리비전 없이 채워진다.
- P1 통계는 깊은 소급이 없어 BACKFILL_ALL 에서는 최근 창 1회, DAILY 에서 매일 누적(`kis.stats.enabled` 기본 true, 2026-09-08 부터).
- ETF NAV(계획 P1 #18)는 2026-09-08 구현. ETN 은 마스터 ticker 가 `Q` 접두 7자라 `BackfillRequest.TICKER`·KIS 종목코드 형식과 맞지 않아 제외(필요 시 코드 정규화 후 EN 그룹 추가).
- 시장별 투자자(계획 P2)는 2026-09-08 구현. 기준일 1회 호출·연속조회 없음이라 날짜 창 페이저 대신 0001 영업일 집합을 역순으로 돈다(휴장일 빈 응답을 소급 한계로 오판하지 않기 위해). 경로·파라미터 의미·금액 단위는 실측 항목(§10).
- 테마 매핑(계획 P2)은 2026-09-08 구현. 테마명 마스터 테이블을 두지 않고 `tb_stock_sector_map.sector_name` 에 보관한다(`tb_stock_index_master` 에 넣으면 INDEX_BACKFILL 대상으로 새어 나감). 줄 끝 10자 종목코드의 체계(6자/A 접두/기타)는 `KisThemeFileManualTest` 로 실측 후 `ThemeCodeRecord.ticker()` 규칙 확정.
- 실시간 웹소켓·Python 분석 환경은 범위 밖(계획대로). `KisMarketDataPort` 가 확장 경계.

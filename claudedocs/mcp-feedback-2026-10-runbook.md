# MCP 점검 피드백(plan/mcp.md) 대응 — 운영 런북과 후속 설계 메모 (2026-10-16)

22개 커밋(커밋 메시지 `plan/mcp.md P0-n/P1-n/P2`)으로 코드는 끝났고, 아래 운영 작업은 사용자가 순서대로 실행한다. 원인 요약은 커밋 메시지와 `~/.claude/plans/...mcp-whimsical-moth.md` 에 있다.

## 1. 배포 전 — 코드만으로 바뀌는 것

| 항목 | 효과 | 확인 |
|---|---|---|
| P0-1·3 후보 feature | 다음 DAILY 부터 `marketCap` 원 단위 1e15 급, `rsIdx20d` 후보마다 다름 | `adviceDetail` candidates |
| P0-6 프롬프트 수급 | `prompt_version='advice-v10'`, 근거 문장 수급이 "억원" | `SELECT base_date, prompt_version FROM tb_advisor_advice ORDER BY 1 DESC LIMIT 3` |
| P1-10 노트 | 다음 12:00 INTRADAY 부터 `z_score` 비null, FLAT 아닌 픽은 회고 | `adviceNotes` |
| P1-7 dataFreshness | 단일 문(인덱스는 migrate 02 뒤 효과) | 로그 `mcp tool=dataFreshness durationMs=` |
| P1-7 screeningTop | 그날 DAILY 가 있으면 `source=STORED` | MCP 호출 |
| P1-8 MORNING | 전일 DAILY 없으면 #hvy-notify 경보 | — |
| P1-12 아침 점검 | `detail_json.index.*.sigmaBasis=GAP`, 임계 ≈ 절반 | `tb_advisor_morning_check` 최신 행 |
| P2 | `_pct` 키, resolveStock 20건·truncated, 재무·기업행사 최신순, codeName, `screenStocks`, listAdvices summary | MCP 호출 |

재기동 필요: `kis.derived.concurrently=true`(커밋 12), `advisor.morning.sigma-basis`, `advisor.theme.names`.

## 2. migrate 적용 (낮 시간, DAILY 18:30 전)

```bash
cd src/main/resources/db/migrate
psql "$DATABASE_URL" -1 -f 20261016_01_pick_note_vol20d_comment.sql
psql "$DATABASE_URL"    -f 20261016_02_freshness_date_indexes.sql     # ⚠ CONCURRENTLY — -1 금지
psql "$DATABASE_URL" -1 -f 20261016_03_corporate_action_fix_rate_percent.sql   # 데이터 UPDATE·DELETE → 바로 §3
psql "$DATABASE_URL" -1 -f 20261016_04_daily_metric_computed_at_comment.sql
psql "$DATABASE_URL" -1 -f 20261016_05_morning_check_sigma_basis_comment.sql
psql "$DATABASE_URL" -1 -f 20261016_06_master_valuation_unit_comments.sql
psql "$DATABASE_URL" -1 -f 20261016_07_sector_zero_valuation_zero.sql          # 데이터 UPDATE → §4
```

순서 제약: **03 은 새 코드 배포 뒤·ADJUST_FACTOR 전**에 적용한다(새 코드로 ADJUST_FACTOR 가 먼저 돌면 ratio 100 이 상한에 걸려 이벤트가 안 만들어지고 옛 0.0099 행이 남는다). 02 는 인덱스 5개가 `pg_indexes` 에 생겼는지 확인(`McpReadPgTest.freshnessIndexesExist` 와 같은 목록).

## 3. 수정주가 재산출 (P0-4)

```bash
B=https://<host>/api/stock/admin/collect; H='Content-Type: application/json'
curl -X POST $B/ADJUST_FACTOR -H "$H" -d '{}'            # run 메타 eventsImplausible·implausible·verification
curl -X POST $B/DERIVED_REFRESH -H "$H" -d '{"force":true}'   # ≈30분. 또는 일요일 WEEKLY 대기
```

```sql
SELECT trade_date, raw_close, adj_close FROM vw_stock_daily_price_adj WHERE ticker='187870' AND trade_date BETWEEN '2026-04-24' AND '2026-04-30';
-- 04-27 adj_close ≈ 19,150 (38,300 × 0.5), 04-28 = 20,600
SELECT ticker, ret_120d FROM tb_stock_daily_metric WHERE trade_date = (SELECT MAX(trade_date) FROM tb_stock_daily_metric) ORDER BY ret_120d DESC LIMIT 10;  -- 23~180배 이상치 소멸
-- 감자(reduce_cap_rate) 단위 실측: observed/price_factor 가 1 근처여야 한다
SELECT e.ticker, e.effective_date, e.price_factor,
       (SELECT close_price FROM tb_stock_daily_price p WHERE p.ticker=e.ticker AND p.trade_date>=e.effective_date ORDER BY trade_date LIMIT 1)
     / NULLIF((SELECT close_price FROM tb_stock_daily_price p WHERE p.ticker=e.ticker AND p.trade_date< e.effective_date ORDER BY trade_date DESC LIMIT 1),0) AS observed
FROM tb_stock_adjust_event e WHERE e.action_type='CAPITAL_REDUCTION' AND e.effective_date<=CURRENT_DATE;
```

## 4. 섹터·밸류 정리 뒤 (P2, migrate 07)

`POST $B/DERIVED_REFRESH`(본문 없음, 증분)로 `mv_stock_sector_daily` 에서 '0000' 섹터를 지운다. 확인: `SELECT COUNT(*) FROM tb_stock_sector_map WHERE source='KRX' AND sector_code ~ '^0+$' AND valid_to IS NULL;` → 0.

## 5. IC_BACKFILL (P1-9·P1-13 H20)

19:30~21:00 을 피해서:
```bash
curl -X POST https://<host>/api/advisor/admin/jobs/IC_BACKFILL          # h=5·20 모두 (또는 ?horizon=5 → ?horizon=20)
```
```sql
SELECT horizon_days, COUNT(DISTINCT trade_date) ic_days, MIN(trade_date), MAX(trade_date) FROM tb_advisor_signal_ic_daily GROUP BY 1;   -- h=5 ≥120, h=20 ≥480
SELECT weight_set_id, horizon_days, source, as_of, window_days, n_eff, is_active FROM tb_advisor_weight_set ORDER BY 1;                  -- BACKFILL is_active, n_eff ≥ 24
SELECT w.signal_code, w.multiplier, w.ic_mean, w.t_stat, w.flagged FROM tb_advisor_signal_weight w JOIN tb_advisor_weight_set s USING (weight_set_id)
WHERE s.is_active AND s.horizon_days = 5 ORDER BY 1;   -- MOM_20D·RS_INDEX·TREND_MA flagged=true, multiplier 0.5
```
MCP `activeAdviceWeights` → source BACKFILL. 금요일 20:10 H20 발행. 주간 보고(일 08:00)가 "n_eff x.x < 24 … IC_BACKFILL" 을 적으면 아직 미실행.

## 6. 10/14 누락 조사 (P1-8)

```sql
SELECT run_id, job_type, trigger_type, status, target_date, started_at, finished_at, finished_at - started_at AS took, metadata_json->'steps', metadata_json->'refreshMs'
FROM tb_stock_collect_run WHERE target_date BETWEEN '2026-10-13' AND '2026-10-15' ORDER BY started_at;
SELECT run_id, job_type, status, base_date, started_at, metadata_json FROM tb_advisor_run
WHERE started_at >= '2026-10-14 10:00+09' AND started_at < '2026-10-15 10:00+09' ORDER BY started_at;
```
기대: 10/14 DAILY 수집이 18:30 → ≈20:29(평소 25분의 5배), 19:55 ADVISE SKIPPED("파생 지표 없음"/"DAILY 수집 미완"), #hvy-error 19:55 알림. 지연이 10/14 투자자 정규화 백필과 겹친 일회성이면 판단 마감 연장(cron 19~20시·deadline 20:55·news/note cutoff 21:00)은 하지 않는다. 보충: `POST /api/advisor/admin/jobs/ADVISE?baseDate=2026-10-14`.

P0-5 확인: `SELECT p.trade_date, p.close_price, p.collected_at, m.adj_close, m.computed_at FROM tb_stock_daily_price p JOIN tb_stock_daily_metric m USING (ticker, trade_date) WHERE ticker='005930' AND trade_date BETWEEN '2026-10-10' AND '2026-10-15' ORDER BY 1;` — adj_close == close_price 면 피드백의 시각 비교는 오탐. 이제 DAILY run 메타 `correctedFrom`·`correctedRows` 로 정정 범위를 본다.

## 7. 기타 실측 (P2 후속 판단용)

- 수급 단위 잔존: `SELECT trade_date, MAX(ABS(foreign_net_amt)) FROM tb_stock_investor_daily WHERE trade_date >= CURRENT_DATE - 10 GROUP BY 1 ORDER BY 1;` → 1e10 이상(백만원 잔존일은 1e4~1e6)
- 마스터 천주 가정 위반: migrate 06 끝의 SQL → 0행
- 섹터 미분류 분포·1032/1033·재무 비율 0 빈도: migrate 07 끝 SQL, `SELECT index_code, index_name, is_active FROM tb_stock_index_master WHERE index_code IN ('1032','1033');`, `SELECT COUNT(*) FILTER (WHERE revenue_growth = 0), COUNT(*) FILTER (WHERE roe = 0), COUNT(*) FILTER (WHERE debt_ratio = 0), COUNT(*) FROM tb_stock_financial;`
- kospi200_sector 코드별 대표 종목(→ `advisor.theme.names`): `SELECT kospi200_sector, COUNT(*), STRING_AGG(stock_name, ',' ORDER BY listed_shares DESC NULLS LAST) FROM tb_stock_master WHERE is_kospi200 AND is_active GROUP BY 1 ORDER BY 1;`
- 추세 채점·H180: `SELECT COUNT(*) FROM tb_advisor_call_score WHERE subject_type='TREND' AND status='SCORED';`, `SELECT * FROM tb_advisor_run WHERE job_type='ADVISE_H180' ORDER BY started_at DESC LIMIT 3;`(없으면 `POST /jobs/ADVISE_H180`)
- 뉴스·EVENT: `scheduler.stock-eventfeed.enabled=true` 는 `GdeltDocManualTest` 1회 실측 뒤 결정(18호출×2회/일). `advisor.news.enabled` 는 별도(judge 2배)

## 8. 후속 설계 메모 (이번에 구현하지 않은 것)

### 8.1 테마 strength 를 동일가중 벤치(rsEq)로
현재 `strength` 는 rs20/rs60(KOSPI 종합 대비)이고 rsEq 는 병기만 한다. 전환하려면: (1) `ThemeStrengthService.strength(rsEq20, rsEq60, …)` + `Theme`/`regime_json` 에 `basis: "KOSPI"|"EQ"` 저장, (2) `advice-system-v10.md` 의 테마 정의 문장("중앙값 − KOSPI") 수정 → **advice-v11 + h20-v4** 동반 상향, (3) 착수 전 측정: 최근 60거래일 두 정의의 STRONG 수 비교
`SELECT base_date, (SELECT COUNT(*) FROM jsonb_array_elements(regime_json->'themes') t WHERE t->>'strength'='STRONG') AS strong_kospi FROM tb_advisor_advice WHERE advice_kind='DAILY' AND variant='LIVE' AND regime_json IS NOT NULL ORDER BY 1 DESC LIMIT 60;` 와 `marketRegime` 의 rsEq20 ≥ 0.02 ∧ rsEq60 > 0 수를 날짜별로 대조. M0 측정 관례(`advisor-m0-measurements.md`)대로 결과가 나온 뒤 결정. Slack `themeBrief` 에 name 표기도 함께.

### 8.2 `tv_ratio_20_60` 지표 컬럼 추가 절차
`tv_avg_20d` 가 없어 기존 컬럼으로 대체 불가. 비용: `stock-schema.sql`·`schema-postgres.sql` 컬럼 2개+주석, migrate `ALTER TABLE tb_stock_daily_metric ADD COLUMN tv_avg_20d, tv_ratio_20_60`, `DerivedViewRefresher.DAILY_METRIC_UPSERT_SQL` 의 cum_tv LAG 20 + INSERT/SET/IS DISTINCT FROM 3곳, 전체 재계산(`DERIVED_REFRESH {"force":true}` ≈25분), `McpFields` METRIC 투영, `MetricColumn`, 가이드. 지금은 `screenStocks(tv_ratio_5_60 GTE 3)` 로 "거래 3배" 질문이 풀리므로 보류.

### 8.3 adviceDetail 후보 축약
`candidates[].feature_json/signal_json` 이 후보 18개 × ~0.5KB. `fields=header|picks|candidates|scores|checks|notes` 선택 파라미터 또는 `candidates=false` 옵션. `latestAdvice`(채팅용 축약)와 역할 정리. 클라이언트별 structuredContent 지원이 확인되면 TextContent 를 요약 1줄로 줄이는 것도 같이.

### 8.4 verify() KIS 대조 창
`AdjustFactorService.verify` 는 420일을 요청해도 KIS 가 최신 100캔들만 줘서 효력일이 오래된 이벤트는 오차 0 → verified=true 로 오판할 수 있다. 표본을 "효력일 ≤ 오늘인 미검증 이벤트 보유 종목" 으로, 창을 [eff−60일, min(today, eff+80일)] 로. 효력일 검산(1.5배 밴드)이 1차 방어선이라 급하지 않다.

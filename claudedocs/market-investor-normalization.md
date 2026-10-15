# 시장별 수급 단위 정규화 (2026-10-15)

`tb_stock_market_investor_daily` 대금을 KIS 원천(백만원)에서 원으로 바꾼다. 데이터 UPDATE 없이 새 매퍼로 재수집하고, 컬럼 COMMENT 만 psql 로 적용한다. 종목 수급 백필(`stock-investor-backfill.md`)과 달리 중지·백업·TRUNCATE 가 없다. 배경과 근거는 `stock-collect.md` §14.

## 0. 사전 확인 (읽기 전용)

```sql
SELECT market_type, MIN(trade_date), MAX(trade_date), COUNT(*) FROM tb_stock_market_investor_daily GROUP BY 1;
-- 백만원 규모 행 수 (세 대표 주체 |값| 최대 < 1억원). 보정 전엔 전체, 보정 후엔 0 이어야 한다
SELECT market_type,
       COUNT(*) FILTER (WHERE GREATEST(ABS(COALESCE(foreign_net_amt,0)), ABS(COALESCE(individual_net_amt,0)),
                               ABS(COALESCE(institution_net_amt,0))) < 100000000) AS still_million,
       COUNT(*) AS total
FROM tb_stock_market_investor_daily GROUP BY 1;
```

## 1. 배포

같은 jar 로 수집기 컨테이너(매퍼 `StockRowMapper.toMarketInvestorRows`)와 MCP 컨테이너(`McpConfig.STOCK_GUIDE` 단위 문구)를 모두 재기동한다. 18:57 KST DAILY 전에 끝내면 그날 DAILY 가 최근 ≈300일을 원으로 덮어쓴다.

## 2. 재수집

```bash
B=https://<host>/api/stock/admin/collect; H='Content-Type: application/json'
curl -X POST $B/MARKET_INVESTOR_BACKFILL -H "$H" -d '{"resetCheckpoint":true}'   # 202. startDate 기본 2015-01-01, endDate 기본 오늘
```

- 체크포인트(KOSPI·KOSDAQ)가 PENDING 으로 돌아가 오늘부터 역순으로 받는다. 호출당 ≈300일 응답이라 시장당 ≈10호출.
- upsert 는 `IS DISTINCT FROM` 이라 전 행이 변경되는 것이 정상(run `rows` ≈ 전체 행 수).
- FAILED/PAUSED 로 멈추면 `resetCheckpoint` 없이 같은 POST 로 재개. 300일 이전 구간만 영향이고 `MarketFeatureService` 는 최근 5행만 읽는다.
- **구버전 수집기로 이 잡을 돌리지 않는다** — 백만원으로 되돌아간다(돌아갔다면 신버전에서 다시 재수집).

## 3. COMMENT 적용

```bash
psql -1 -f src/main/resources/db/migrate/20261015_01_market_investor_unit_comments.sql
```

`McpReadService.catalog()` 가 `col_description()` 을 라이브로 읽으므로 적용 즉시 `stock://catalog` 필드 설명이 "(원)" / "(천주, 원천 그대로)" 로 바뀐다. 타입 변경이 없어 `ddl-auto=validate` 에 영향 없다.

## 4. 사후 검증 (19:30 advisor 전)

```sql
-- 0 이어야 함
SELECT market_type, COUNT(*) FILTER (WHERE GREATEST(ABS(COALESCE(foreign_net_amt,0)), ABS(COALESCE(individual_net_amt,0)),
       ABS(COALESCE(institution_net_amt,0))) < 100000000) AS still_million, COUNT(*) AS total
FROM tb_stock_market_investor_daily GROUP BY 1;

-- 실측 대조: 기대 frgn_eok -45789, inst_eok 16685, indi_eok 23169, foreign_net_qty -4383
SELECT trade_date, foreign_net_amt/1e8 AS frgn_eok, institution_net_amt/1e8 AS inst_eok, individual_net_amt/1e8 AS indi_eok, foreign_net_qty
FROM tb_stock_market_investor_daily WHERE market_type='KOSPI' AND trade_date='2026-10-13';

-- 종목 합계 대조 (ratio ≈ 0.95~1.05)
SELECT m.market_type, i.trade_date, mi.foreign_net_amt AS market_won, SUM(i.foreign_net_amt) AS stock_sum_won,
       ROUND(SUM(i.foreign_net_amt)::numeric / NULLIF(mi.foreign_net_amt,0), 3) AS ratio
FROM tb_stock_investor_daily i JOIN tb_stock_master m ON m.ticker = i.ticker
JOIN tb_stock_market_investor_daily mi ON mi.market_type = m.market_type AND mi.trade_date = i.trade_date
WHERE i.trade_date >= CURRENT_DATE - 14 GROUP BY 1, 2, mi.foreign_net_amt ORDER BY 1, 2;
```

MCP:
- `marketOverview(asOf=2026-10-13)` → `flowUnit "억원"`, `flows[KOSPI].frgn1 ≈ -45789`, `inst1 ≈ 16685`, `indi1 ≈ 23169`. KOSDAQ 도 0 이 아니다.
- `marketSeries(MARKET_INVESTOR, KOSPI, 2026-10-13)` → `foreign_net_amt -4578915000000`, `foreign_net_qty -4383`.
- `stock://catalog` → `tb_stock_market_investor_daily` 필드 설명과 `units` 가이드 문구(MCP 재기동 후).

## 5. 남는 것

- 과거 advisor 판단 프롬프트에 들어간 시장 수급(백만원을 원으로 읽음)은 소급 수정하지 않는다.
- 원 미만 소수·long 범위 초과가 이제 수집 실패로 드러난다. DAILY `steps[].failures` 에 MARKET_INVESTOR 가 뜨면 KIS 응답 포맷 변화를 의심한다.

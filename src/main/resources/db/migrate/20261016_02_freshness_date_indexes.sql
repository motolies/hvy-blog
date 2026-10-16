-- dataFreshness 용 날짜 선두 인덱스 5개 (2026-10-16). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- ⚠ CONCURRENTLY 는 트랜잭션 블록 안에서 실패한다 — 반드시 -1 없이: psql -f src/main/resources/db/migrate/20261016_02_freshness_date_indexes.sql
-- MCP dataFreshness 는 소스별 최신 날짜를 ORDER BY date DESC LIMIT 1 로 읽는데, MV 3개는 유니크 (code, trade_date) 만 있고 global 은 PK (symbol, trade_date),
-- financial 은 (ticker, available_from) 라 날짜 선두 인덱스가 없어 전체 스캔 → 합이 트랜잭션 10s 를 넘겨 항상 timeout 이었다(plan/mcp.md P1-7).
-- MV 인덱스는 REFRESH(일반·CONCURRENTLY)에서 유지되고 stock-derived-rebuild 로 DROP 할 때만 사라진다(정본 stock-derived.sql 에도 추가됨).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mv_stock_index_metric_date         ON mv_stock_index_metric (trade_date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mv_stock_sector_daily_date         ON mv_stock_sector_daily (trade_date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mv_stock_market_breadth_daily_date ON mv_stock_market_breadth_daily (trade_date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_global_market_daily_date     ON tb_stock_global_market_daily (trade_date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_financial_available_from     ON tb_stock_financial (available_from);
-- 확인: EXPLAIN (ANALYZE) SELECT trade_date FROM mv_stock_index_metric WHERE trade_date <= current_date ORDER BY trade_date DESC LIMIT 1;  → Index Only Scan Backward
--       배포 후 로그 "mcp tool=dataFreshness durationMs=" 가 수십 ms 인지

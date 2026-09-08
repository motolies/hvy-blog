-- =============================================
-- 파생 계층 재구축용 DROP 스크립트
-- MV 정의를 바꿨을 때 쓴다. stock-derived.sql 의 CREATE MATERIALIZED VIEW IF NOT EXISTS 는 이미 있는 MV 를 바꾸지 못하므로
-- 의존 역순으로 지운 뒤 stock-derived.sql 을 다시 적용한다. 한 트랜잭션으로 묶어 소비자가 뷰 부재를 보지 않게 한다.
--   cat db/stock-derived-rebuild.sql db/stock-derived.sql | psql -1 "$DATABASE_URL"
-- 이 파일은 schema-postgres.sql 에 임베드하지 않는다 (DROP TABLE ... CASCADE 가 파생 객체를 함께 지운다).
-- =============================================
DROP VIEW IF EXISTS vw_stock_universe_daily;
DROP MATERIALIZED VIEW IF EXISTS mv_stock_sector_daily;
DROP MATERIALIZED VIEW IF EXISTS mv_stock_daily_metric;
DROP VIEW IF EXISTS vw_stock_daily_price_adj;
DROP MATERIALIZED VIEW IF EXISTS mv_stock_adjust_factor;
DROP MATERIALIZED VIEW IF EXISTS mv_stock_index_metric;
DROP VIEW IF EXISTS vw_stock_market_calendar;

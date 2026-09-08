-- =============================================
-- 2026-09-08 재무 성장성·수익성·안정성 3종 추가에 따른 tb_stock_financial 컬럼 승격
-- 기존 운영 DB 에만 필요하다 (stock-schema.sql 의 CREATE TABLE IF NOT EXISTS 는 기존 테이블을 바꾸지 못한다).
-- 재실행 안전. 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260908_01_financial_ratio_columns.sql
-- =============================================
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS operating_profit_growth NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS equity_growth           NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS asset_growth            NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS roa                     NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS net_margin              NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS gross_margin            NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS current_ratio           NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS quick_ratio             NUMERIC(12,4) DEFAULT NULL;
ALTER TABLE tb_stock_financial ADD COLUMN IF NOT EXISTS borrowing_dependency    NUMERIC(12,4) DEFAULT NULL;

COMMENT ON COLUMN tb_stock_financial.profit_growth           IS '순이익 증가율 (%) (재무비율 ntin_inrt. 영업이익 증가율은 operating_profit_growth)';
COMMENT ON COLUMN tb_stock_financial.operating_profit_growth IS '영업이익 증가율 (%) (성장성 bsop_prfi_inrt)';
COMMENT ON COLUMN tb_stock_financial.equity_growth           IS '자기자본 증가율 (%) (성장성 equt_inrt)';
COMMENT ON COLUMN tb_stock_financial.asset_growth            IS '총자산 증가율 (%) (성장성 totl_aset_inrt)';
COMMENT ON COLUMN tb_stock_financial.roa                     IS '총자본 순이익율 ROA (%) (수익성 cptl_ntin_rate)';
COMMENT ON COLUMN tb_stock_financial.net_margin              IS '매출액 순이익율 (%) (수익성 sale_ntin_rate)';
COMMENT ON COLUMN tb_stock_financial.gross_margin            IS '매출액 총이익율 (%) (수익성 sale_totl_rate)';
COMMENT ON COLUMN tb_stock_financial.current_ratio           IS '유동비율 (%) (안정성 crnt_rate)';
COMMENT ON COLUMN tb_stock_financial.quick_ratio             IS '당좌비율 (%) (안정성 quck_rate)';
COMMENT ON COLUMN tb_stock_financial.borrowing_dependency    IS '차입금 의존도 (%) (안정성 bram_depn)';

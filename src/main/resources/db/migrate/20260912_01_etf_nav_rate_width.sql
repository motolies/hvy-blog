-- =============================================
-- 2026-09-12 ETF NAV 비율 컬럼 정밀도 확장 NUMERIC(8,4) → NUMERIC(12,4)
-- 2026-09-09 운영 ETF_NAV_BACKFILL 에서 265690 이 "numeric field overflow (precision 8, scale 4)" 로 실패했다.
-- NAV 가 0/누락인 날 KIS 가 등락률·괴리율에 10^4 이상 값을 주므로 저장 폭을 넓힌다 (|값| ≥ 10000 은 NAV 없음으로 해석).
-- 정밀도만 늘리고 scale 은 그대로라 PostgreSQL 이 테이블을 다시 쓰지 않는다(메타데이터 변경). 재실행 안전.
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260912_01_etf_nav_rate_width.sql
-- =============================================
ALTER TABLE tb_stock_etf_nav_daily
    ALTER COLUMN change_rate     TYPE NUMERIC(12,4),
    ALTER COLUMN nav_change_rate TYPE NUMERIC(12,4),
    ALTER COLUMN disparity_rate  TYPE NUMERIC(12,4);
COMMENT ON COLUMN tb_stock_etf_nav_daily.disparity_rate IS '괴리율 (%, dprt). NAV 가 0/누락이면 KIS 가 10^4 이상 값을 주므로 |값| >= 10000 은 NAV 없음으로 취급 (2026-09-09 265690)';

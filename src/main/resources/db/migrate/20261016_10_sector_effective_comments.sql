-- KRX 섹터 매핑 규칙 주석 (2026-10-16): 중분류, 없으면 비집계 대분류. 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_10_sector_effective_comments.sql  (언제든)
-- 배경(MCP 2차 점검 #3): KRX 마스터는 제조업만 대분류 0027(제조 집계) 아래 중분류(0013 전기·전자 등)를 두고, 비제조업은 대분류 자체가 업종이다
-- (HMM·대한항공 0019 운송·창고, KB금융 0021 금융 — 중분류 null). 중분류만 보던 매핑이 비제조업 전체를 미분류로 남겨 SECTOR_* 시그널이 0.5 였다.
-- 새 코드(StockMasterService.effectiveSectorCode)가 다음 MASTER 실행 때 매핑을 연다 → POST $B/MASTER → DERIVED_REFRESH → IC_BACKFILL(전체).
COMMENT ON COLUMN tb_stock_sector_map.sector_code   IS '섹터 코드. KRX = 지수업종 중분류, 중분류가 없으면 대분류(비제조업 0019 운송·창고·0021 금융 등. 제조 집계 0027·1009 와 시장 집계 0001~0004·1001~1004 는 쓰지 않음, 2026-10-16) | THEME 테마코드 | CUSTOM';
COMMENT ON COLUMN tb_stock_master.sector_large_code IS '지수업종 대분류 코드 (제조업은 집계 0027·KOSDAQ 1009, 비제조업은 업종 자체 — 0019 운송·창고·0021 금융 등)';
COMMENT ON COLUMN tb_stock_master.sector_mid_code   IS '지수업종 중분류 코드 (제조업만 채워짐. KRX 섹터 매핑은 중분류, 없으면 비집계 대분류 — 2026-10-16)';
-- 사전 측정(비제조업은 mid_null = total, 0027·1009 는 mid_null ≈ 0):
--   SELECT market_type, sector_large_code, COUNT(*) FILTER (WHERE sector_mid_code IS NULL) mid_null, COUNT(*) total
--     FROM tb_stock_master WHERE is_active AND security_group = 'ST' GROUP BY 1, 2 ORDER BY 1, 2;
-- MASTER 실행 뒤 확인:
--   SELECT sector_code, sector_name, COUNT(*) FROM tb_stock_sector_map WHERE source = 'KRX' AND valid_to IS NULL GROUP BY 1, 2 ORDER BY 1;  -- 0016~0026 등장, 0027·1009 없음
--   SELECT COUNT(*) FROM tb_stock_master m WHERE is_active AND security_group = 'ST'
--      AND NOT EXISTS (SELECT 1 FROM tb_stock_sector_map s WHERE s.ticker = m.ticker AND s.source = 'KRX' AND s.valid_to IS NULL);  -- 분류 코드 전무(KOSDAQ 일부)만 남음

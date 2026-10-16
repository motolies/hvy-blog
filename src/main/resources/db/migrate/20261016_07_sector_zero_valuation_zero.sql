-- 지수업종 "0000"(미분류) → NULL, 밸류에이션 PER/PBR/EPS/BPS 0 → NULL (2026-10-16). 데이터 UPDATE 있음, 타입 변경 없음.
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_07_sector_zero_valuation_zero.sql  (수집·advisor 스케줄이 돌지 않는 낮 시간)
-- 배경(plan/mcp.md P2): MasterRecord 가 "0000" 을 섹터 코드로 넘겨 tb_stock_sector_map 에 sector_name='0000' 행이 생기고 stockSnapshot·판단 후보 sectorName 이
-- "0000" 으로 나왔다(HMM·한국앤컴퍼니·동서·팬오션). KIS 현재가의 PER/PBR "0.00"(미산출)이 0 으로 저장돼 VALUE_RANK 가 최고 밸류로 읽고 스냅샷이 0.0 을 냈다.
-- 새 코드(MasterRecord.sectorCode·StockRowMapper.nonZero)가 앞으로의 행을 막고, 이 파일이 기존 행을 정리한다. 다음 MASTER 실행(05:30)도 같은 정리를 한다.
UPDATE tb_stock_master SET sector_large_code = NULL WHERE sector_large_code ~ '^0+$';
UPDATE tb_stock_master SET sector_mid_code   = NULL, updated_at = NOW() WHERE sector_mid_code ~ '^0+$';
UPDATE tb_stock_master SET sector_small_code = NULL WHERE sector_small_code ~ '^0+$';
-- 당일 열린 '0000' 매핑은 지우고, 그 전 것은 오늘로 닫는다(SCD2 이력 보존)
DELETE FROM tb_stock_sector_map WHERE source = 'KRX' AND sector_code ~ '^0+$' AND valid_to IS NULL AND valid_from = CURRENT_DATE;
UPDATE tb_stock_sector_map SET valid_to = CURRENT_DATE WHERE source = 'KRX' AND sector_code ~ '^0+$' AND valid_to IS NULL;
-- 밸류 0 → NULL (IC 학습 대상이 아닌 VALUE_RANK 만 영향, 과거 IC 불변)
UPDATE tb_stock_valuation_daily
SET per = NULLIF(per, 0), pbr = NULLIF(pbr, 0), eps = NULLIF(eps, 0), bps = NULLIF(bps, 0)
WHERE per = 0 OR pbr = 0 OR eps = 0 OR bps = 0;
-- 이후 POST /api/stock/admin/collect/DERIVED_REFRESH (mv_stock_sector_daily 에서 '0000' 섹터가 사라진다)
-- 확인: SELECT COUNT(*) FROM tb_stock_sector_map WHERE source='KRX' AND sector_code ~ '^0+$' AND valid_to IS NULL;  → 0
--       SELECT COUNT(*) FROM tb_stock_valuation_daily WHERE per = 0 OR pbr = 0;  → 0
-- 섹터 미분류 근본 원인 실측(별도 작업 판단용):
--   SELECT sector_mid_code, COUNT(*) FROM tb_stock_master WHERE is_active AND security_group = 'ST' GROUP BY 1 ORDER BY 2 DESC;
--   SELECT ticker, stock_name, market_type FROM tb_stock_master WHERE is_active AND security_group = 'ST' AND sector_mid_code IS NULL;
--   SELECT sm.sector_code, COUNT(*) FROM tb_stock_sector_map sm LEFT JOIN tb_stock_index_master im ON im.index_code = sm.sector_code
--    WHERE sm.source = 'KRX' AND sm.valid_to IS NULL AND im.index_code IS NULL GROUP BY 1;   -- 업종 지수 없는 섹터(SECTOR_MOM null → 0.5)

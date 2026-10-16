-- tb_stock_master·tb_stock_master_history·tb_stock_valuation_daily 단위 주석 정정 (2026-10-16). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_06_master_valuation_unit_comments.sql
-- McpReadService.catalog() 가 col_description() 을 라이브로 읽어 stock://catalog 필드 설명으로 내보내므로 운영 DB 에 직접 적용한다(plan/mcp.md P2 단위).
-- 값은 바꾸지 않는다: listed_shares 를 주로 환산하면 MasterHistoryRow 해시가 바뀌어 SCD2 2,700행이 새로 생기고 이력 단위가 단절된다. 주 단위 정본은 VALUATION.listed_shares.
COMMENT ON COLUMN tb_stock_master.listed_shares         IS '상장주식수 (천주, 마스터 파일 lstn_stcn 원천 그대로 — 삼성전자 5,846,278; 주 단위 정본은 tb_stock_valuation_daily.listed_shares. 2026-10-16 실측)';
COMMENT ON COLUMN tb_stock_master.capital               IS '자본금 (원, 마스터 파일 원천 그대로 — 헤더 표기는 백만원이나 실제 값은 원. 2026-10-16 실측)';
COMMENT ON COLUMN tb_stock_master_history.listed_shares IS '상장주식수 (천주, tb_stock_master.listed_shares 와 같은 단위)';
COMMENT ON COLUMN tb_stock_valuation_daily.market_cap   IS '시가총액 (원 — KIS hts_avls 억원을 StockRowMapper 가 ×1e8 환산 저장. 삼성전자 ≈1.37e15)';
COMMENT ON COLUMN tb_stock_valuation_daily.per          IS 'PER (배). NULL = 결측 또는 산출 불가(적자 등, KIS 0.00 — 2026-10-16 부터 NULL 저장)';
COMMENT ON COLUMN tb_stock_valuation_daily.pbr          IS 'PBR (배). NULL = 결측 또는 산출 불가(자본잠식 등, KIS 0.00 — 2026-10-16 부터 NULL 저장)';
COMMENT ON COLUMN tb_stock_valuation_daily.eps          IS 'EPS (원). NULL = 결측 또는 미산출(KIS 0.00 — 2026-10-16 부터 NULL 저장), 음수는 적자';
COMMENT ON COLUMN tb_stock_valuation_daily.bps          IS 'BPS (원). NULL = 결측 또는 미산출(KIS 0.00 — 2026-10-16 부터 NULL 저장)';
-- 천주 가정 위반 행 탐지(없어야 정상):
-- SELECT m.ticker, m.listed_shares AS master_k, v.listed_shares AS valuation FROM tb_stock_master m
--   JOIN LATERAL (SELECT listed_shares FROM tb_stock_valuation_daily WHERE ticker = m.ticker ORDER BY trade_date DESC LIMIT 1) v ON TRUE
--  WHERE m.listed_shares IS NOT NULL AND ABS(m.listed_shares * 1000 - v.listed_shares) > v.listed_shares * 0.01;

-- tb_stock_financial 단위·의미 주석 정정 (2026-10-16): 금액 억원, Q 손익 회계연도 누적, 비율 0 → NULL. 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_09_financial_unit_comments.sql  (20261016_08 과 함께)
-- McpReadService.catalog() 가 col_description() 을 라이브로 읽어 stock://catalog 필드 설명으로 내보내므로 운영 DB 에 직접 적용한다(MCP 2차 점검 #12).
-- 금액은 "백만원" 으로 적혀 있었지만 실제는 억원이다(삼성전자 202512 매출 3,336,059 = 333.6조원 — 백만원이면 3.3조).
COMMENT ON COLUMN tb_stock_financial.period_type      IS 'Y 연간(결산월 행만, 2026-10-16) | Q 분기 — Q 손익은 회계연도 누적(YTD: 12월 결산 202603=1분기, 202606=상반기, 202612=연간), 대차는 기말 잔액';
COMMENT ON COLUMN tb_stock_financial.revenue          IS '매출액 (억원, KIS 원천 그대로 — 삼성전자 202512 3,336,059 = 333.6조원. Q 는 회계연도 누적. 2026-10-16 실측)';
COMMENT ON COLUMN tb_stock_financial.operating_profit IS '영업이익 (억원, Q 는 회계연도 누적)';
COMMENT ON COLUMN tb_stock_financial.net_income       IS '당기순이익 (억원, Q 는 회계연도 누적)';
COMMENT ON COLUMN tb_stock_financial.total_asset      IS '자산총계 (억원, 기말 잔액)';
COMMENT ON COLUMN tb_stock_financial.total_equity     IS '자본총계 (억원, 기말 잔액)';
COMMENT ON COLUMN tb_stock_financial.total_debt       IS '부채총계 (억원, 기말 잔액)';
COMMENT ON COLUMN tb_stock_financial.roe              IS 'ROE (%). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.debt_ratio       IS '부채비율 (%). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.revenue_growth   IS '매출액 증가율 (%). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.profit_growth    IS '순이익 증가율 (%) (재무비율 ntin_inrt. 영업이익 증가율은 operating_profit_growth). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.operating_profit_growth IS '영업이익 증가율 (%) (성장성 bsop_prfi_inrt). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.equity_growth    IS '자기자본 증가율 (%) (성장성 equt_inrt). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.asset_growth     IS '총자산 증가율 (%) (성장성 totl_aset_inrt). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.roa              IS '총자본 순이익율 ROA (%) (수익성 cptl_ntin_rate). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.net_margin       IS '매출액 순이익율 (%) (수익성 sale_ntin_rate). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.gross_margin     IS '매출액 총이익율 (%) (수익성 sale_totl_rate). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.current_ratio    IS '유동비율 (%) (안정성 crnt_rate). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.quick_ratio      IS '당좌비율 (%) (안정성 quck_rate). NULL = 결측 — KIS 0.00(공란)은 2026-10-16 부터 NULL';
COMMENT ON COLUMN tb_stock_financial.borrowing_dependency IS '차입금 의존도 (%) (안정성 bram_depn). 0 = 무차입 또는 결측(구분 불가, 0 유지)';

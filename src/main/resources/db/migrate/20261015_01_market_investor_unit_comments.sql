-- tb_stock_market_investor_daily 단위 주석 갱신 (2026-10-15). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261015_01_market_investor_unit_comments.sql
-- McpReadService.catalog() 가 col_description() 을 라이브로 읽어 stock://catalog 필드 설명으로 내보내므로 운영 DB 에 직접 적용한다.
-- 데이터 보정은 UPDATE 가 아니라 새 매퍼(StockRowMapper.toMarketInvestorRows: 대금 백만원 → 원 ×1e6, 수량 천주 그대로)로
-- MARKET_INVESTOR_BACKFILL 을 {"resetCheckpoint":true} 로 재수집해서 한다. 절차: claudedocs/market-investor-normalization.md
COMMENT ON TABLE  tb_stock_market_investor_daily                          IS '시장별(KOSPI|KOSDAQ) 투자자 15주체 순매수 대금(원)·수량(천주) 일별 (FHPTJ04040000). KIS 원천 대금은 백만원이며 StockRowMapper 가 저장 시 ×1,000,000 환산, 수량은 원천 천주 그대로 (2026-10-15)';
COMMENT ON COLUMN tb_stock_market_investor_daily.market_type              IS '시장 구분: KOSPI | KOSDAQ (KIS 파라미터 KSP | KSQ)';
COMMENT ON COLUMN tb_stock_market_investor_daily.trade_date               IS '영업일 (stck_bsop_date)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_net_amt          IS '외국인 순매수 대금(원) (frgn_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_net_qty          IS '외국인 순매수 수량(천주, 원천 그대로) (frgn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_reg_net_amt      IS '외국인 등록 순매수 대금(원) (frgn_reg_ntby_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_reg_net_qty      IS '외국인 등록 순매수 수량(천주, 원천 그대로) (frgn_reg_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_nreg_net_amt     IS '외국인 비등록 순매수 대금(원) (frgn_nreg_ntby_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_nreg_net_qty     IS '외국인 비등록 순매수 수량(천주, 원천 그대로) (frgn_nreg_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.individual_net_amt       IS '개인 순매수 대금(원) (prsn_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.individual_net_qty       IS '개인 순매수 수량(천주, 원천 그대로) (prsn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.institution_net_amt      IS '기관계 순매수 대금(원) (orgn_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.institution_net_qty      IS '기관계 순매수 수량(천주, 원천 그대로) (orgn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.securities_net_amt       IS '증권(금융투자) 순매수 대금(원) (scrt_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.securities_net_qty       IS '증권(금융투자) 순매수 수량(천주, 원천 그대로) (scrt_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.invest_trust_net_amt     IS '투자신탁 순매수 대금(원) (ivtr_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.invest_trust_net_qty     IS '투자신탁 순매수 수량(천주, 원천 그대로) (ivtr_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.private_fund_net_amt     IS '사모펀드 순매수 대금(원) (pe_fund_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.private_fund_net_qty     IS '사모펀드 순매수 수량(천주, 원천 그대로) (pe_fund_ntby_vol)';
COMMENT ON COLUMN tb_stock_market_investor_daily.bank_net_amt             IS '은행 순매수 대금(원) (bank_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.bank_net_qty             IS '은행 순매수 수량(천주, 원천 그대로) (bank_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.insurance_net_amt        IS '보험 순매수 대금(원) (insu_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.insurance_net_qty        IS '보험 순매수 수량(천주, 원천 그대로) (insu_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.merchant_bank_net_amt    IS '종금 순매수 대금(원) (mrbn_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.merchant_bank_net_qty    IS '종금 순매수 수량(천주, 원천 그대로) (mrbn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.pension_net_amt          IS '기금(연기금) 순매수 대금(원) (fund_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.pension_net_qty          IS '기금(연기금) 순매수 수량(천주, 원천 그대로) (fund_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_net_amt            IS '기타 순매수 대금(원) (etc_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_net_qty            IS '기타 순매수 수량(천주, 원천 그대로) (etc_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_org_net_amt        IS '기타 단체 순매수 대금(원) (etc_orgt_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_org_net_qty        IS '기타 단체 순매수 수량(천주, 원천 그대로) (etc_orgt_ntby_vol)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_corp_net_amt       IS '기타 법인 순매수 대금(원) (etc_corp_ntby_tr_pbmn 백만원 ×1e6)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_corp_net_qty       IS '기타 법인 순매수 수량(천주, 원천 그대로) (etc_corp_ntby_vol)';
COMMENT ON COLUMN tb_stock_market_investor_daily.collected_at             IS '마지막 수집 시각';

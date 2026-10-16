-- 재무 연간(Y) 진행 중 회계연도 중간기간 행 삭제 · 비율 0(KIS 공란) → NULL (2026-10-16). 데이터 DELETE·UPDATE 있음, 타입 변경 없음.
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_08_financial_interim_annual_ratio_zero.sql
-- ⚠ 새 FinancialRowMapper 배포 직전에 적용하고, 적용~배포 사이 FINANCIAL_BACKFILL·WEEKLY(일 03:00) 를 돌리지 않는다.
--   StockFinancialWriter 는 최신 리비전과 값 19개를 비교하므로 0↔NULL 차이만으로도 revision_seq 가 오른다(어느 쪽이 먼저든 사이에 수집이 끼면 같다).
-- 배경(MCP 2차 점검 #12): KIS 연간(FID_DIV_CLS_CODE=0) 응답이 진행 중 회계연도의 최근 중간기간(12월 결산 202606)을 함께 줘 Y 로 저장됐고,
--   장기 팩터(LongTermScreeningService, period_type Y 최신)가 반기 누적을 연간으로 썼다. 202606 Y 의 quick_ratio 0(Q 는 229.73)처럼 0 은 KIS 공란 표기다.
-- 결산월이 아닌 Y 행은 그보다 뒤에 결산월 Y 행이 있을 때(결산월 변경 전 옛 회계연도)만 남긴다 — FinancialRowMapper.keepAnnual 과 같은 규칙.
-- 사전 측정:
--   SELECT COUNT(*), COUNT(DISTINCT f.ticker), MIN(f.fiscal_period), MAX(f.fiscal_period) FROM tb_stock_financial f JOIN tb_stock_master m USING (ticker)
--    WHERE f.period_type = 'Y' AND m.settle_month IS NOT NULL AND right(f.fiscal_period, 2) <> lpad(m.settle_month, 2, '0');
--   SELECT COUNT(*) FILTER (WHERE roe = 0) roe0, COUNT(*) FILTER (WHERE debt_ratio = 0) debt0, COUNT(*) FILTER (WHERE quick_ratio = 0) quick0,
--          COUNT(*) FILTER (WHERE revenue_growth = 0) rg0, COUNT(*) FILTER (WHERE borrowing_dependency = 0) bram0, COUNT(*) FROM tb_stock_financial;
DELETE FROM tb_stock_financial f
USING tb_stock_master m
WHERE m.ticker = f.ticker AND f.period_type = 'Y'
  AND m.settle_month ~ '^(0?[1-9]|1[0-2])$'
  AND right(f.fiscal_period, 2) <> lpad(m.settle_month, 2, '0')
  AND NOT EXISTS (SELECT 1 FROM tb_stock_financial g
                  WHERE g.ticker = f.ticker AND g.period_type = 'Y'
                    AND right(g.fiscal_period, 2) = lpad(m.settle_month, 2, '0')
                    AND g.fiscal_period > f.fiscal_period);
-- 비율 0 → NULL. 차입금 의존도(borrowing_dependency)는 무차입 기업의 실제 0 이 흔해 제외
UPDATE tb_stock_financial
SET roe = NULLIF(roe, 0), debt_ratio = NULLIF(debt_ratio, 0), revenue_growth = NULLIF(revenue_growth, 0),
    profit_growth = NULLIF(profit_growth, 0), operating_profit_growth = NULLIF(operating_profit_growth, 0),
    equity_growth = NULLIF(equity_growth, 0), asset_growth = NULLIF(asset_growth, 0), roa = NULLIF(roa, 0),
    net_margin = NULLIF(net_margin, 0), gross_margin = NULLIF(gross_margin, 0),
    current_ratio = NULLIF(current_ratio, 0), quick_ratio = NULLIF(quick_ratio, 0)
WHERE roe = 0 OR debt_ratio = 0 OR revenue_growth = 0 OR profit_growth = 0 OR operating_profit_growth = 0
   OR equity_growth = 0 OR asset_growth = 0 OR roa = 0 OR net_margin = 0 OR gross_margin = 0
   OR current_ratio = 0 OR quick_ratio = 0;
-- 확인: 사전 측정 첫 SQL → 0
--       SELECT fiscal_period, period_type FROM tb_stock_financial WHERE ticker = '005930' AND fiscal_period >= '202512' ORDER BY 1, 2;  → 202512 Q·Y, 202603 Q, 202606 Q
-- 일요일 WEEKLY 뒤: 리비전 폭증·중간기간 Y 재유입 없음
--       SELECT COUNT(*) FROM tb_stock_financial WHERE revision_seq > 0 AND first_seen_at >= '2026-10-18';

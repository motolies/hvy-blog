-- =============================================
-- 주식 수집 모듈 시드 데이터 (psql 수기 적용, 재실행 안전)
-- =============================================
-- 국내 섹터 ↔ 미국 참조 지표 매핑 (스펙 §4.4). sector_code 는 CUSTOM 섹터 코드다 —
-- KRX 지수업종 중분류와 1:1 이 아니므로 tb_stock_sector_map 에 source='CUSTOM' 행을 붙여 쓴다.
-- 심볼은 kis.overseas.symbols 와 같아야 tb_stock_global_market_daily 와 조인된다. 미제공 지표(WTI·10년물·LNG)는 제외.
-- =============================================
INSERT INTO tb_stock_global_sector_map (sector_code, global_symbol, weight) VALUES
    ('SEMICON', 'COMP', 1.0), ('SEMICON', 'SOX', 1.0), ('SEMICON', 'SMH', 1.0), ('SEMICON', 'SOXX', 1.0),
    ('SEMICON', 'NVDA', 1.0), ('SEMICON', 'AMD', 1.0), ('SEMICON', 'MU', 1.0), ('SEMICON', 'TSM', 1.0), ('SEMICON', 'ASML', 1.0),
    ('AI_DC', 'COMP', 1.0), ('AI_DC', 'NVDA', 1.0), ('AI_DC', 'MSFT', 1.0), ('AI_DC', 'GOOGL', 1.0), ('AI_DC', 'AMZN', 1.0),
    ('BATTERY', 'TSLA', 1.0), ('BATTERY', 'ALB', 1.0), ('BATTERY', 'LIT', 1.0),
    ('BIO', 'XBI', 1.0), ('BIO', 'IBB', 1.0),
    ('DEFENSE', 'ITA', 1.0), ('DEFENSE', 'LMT', 1.0), ('DEFENSE', 'RTX', 1.0), ('DEFENSE', 'NOC', 1.0),
    ('FINANCE', 'XLF', 1.0), ('FINANCE', 'KRE', 1.0),
    ('ENERGY_CHEM', 'XLE', 1.0),
    ('INTERNET', 'COMP', 1.0),
    ('ROBOT', 'BOTZ', 1.0), ('ROBOT', 'ROBO', 1.0), ('ROBOT', 'TSLA', 1.0), ('ROBOT', 'NVDA', 1.0),
    ('MARKET', '.DJI', 1.0), ('MARKET', 'SPX', 1.0), ('MARKET', 'COMP', 1.0), ('MARKET', 'FX@KRW', 1.0)
ON CONFLICT (sector_code, global_symbol) DO NOTHING;

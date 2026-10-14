-- 버전 참조 MV가 있을 수 있으므로 stock-derived-rebuild.sql → 이 파일 → stock-derived.sql 순서로 적용한다.
-- 수집·파생·advisor 스케줄과 MCP 중지 후 적용한다. 기존 금액은 환산하지 않는다.
ALTER TABLE tb_stock_investor_daily
    DROP COLUMN IF EXISTS normalization_version;
ALTER TABLE tb_stock_investor_daily
    ALTER COLUMN foreign_net_amt DROP NOT NULL,
    ALTER COLUMN foreign_net_amt DROP DEFAULT,
    ALTER COLUMN institution_net_amt DROP NOT NULL,
    ALTER COLUMN institution_net_amt DROP DEFAULT,
    ALTER COLUMN individual_net_amt DROP NOT NULL,
    ALTER COLUMN individual_net_amt DROP DEFAULT,
    ALTER COLUMN pension_net_amt DROP NOT NULL,
    ALTER COLUMN pension_net_amt DROP DEFAULT,
    ALTER COLUMN other_net_amt DROP NOT NULL,
    ALTER COLUMN other_net_amt DROP DEFAULT;
-- 재수집 후 DERIVED_REFRESH {"force":true} 완료 전 소비자를 재개하지 않는다.

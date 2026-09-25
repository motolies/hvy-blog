-- =============================================
-- 2026-09-25 advisor 합성 국면·정책 표(M6, regime-policy-v1, advice-v8) 증분 (기존 운영 DB 전용, 재실행 안전)
-- 신규 설치는 db/advisor-schema.sql 로 충분하다. 기존 DB 는 CREATE TABLE IF NOT EXISTS 가 있는 테이블에 컬럼을 넣지 못하므로
-- 이 파일이 tb_advisor_advice 맨 뒤에 regime_json 을 붙이고 주석을 단다. 인덱스는 추가하지 않는다(조회는 advice_id·base_date 로만).
-- 적용 순서: 이 SQL → 앱 배포(advice-v8). 배포 전 앱은 이 컬럼을 모르므로 먼저 적용해도 안전하다(INSERT 가 컬럼을 나열한다).
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260925_03_advisor_regime_policy.sql
-- 확인: SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'tb_advisor_advice' AND column_name = 'regime_json';  -- 1행, jsonb
--       배포 다음 날: SELECT base_date, variant, regime_json->>'trend', regime_json->>'vol', regime_json->'policy' FROM tb_advisor_advice
--                     WHERE advice_kind = 'DAILY' ORDER BY advice_id DESC LIMIT 5;   -- LIVE·LLM 섀도는 채워지고 QUANT 섀도는 NULL
-- =============================================

ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS regime_json JSONB DEFAULT NULL;

COMMENT ON COLUMN tb_advisor_advice.regime_json        IS '합성 국면 스냅샷 (M6, regime-policy-v1, 2026-09-25): {indexCode, tradeDate, trend, trendScore, vol LOW|NORMAL|HIGH|UNKNOWN, volPct(σ20 의 기준일 이전 최대 5년 분포 백분위), sigma20, volHistoryDays, policy{version,longMax,convictionCap,avoidMax}, themes[{code,members,rs5,rs20,rs60,breadth,strength,leaders}]}. DAILY·ADHOC LIVE 와 LLM 섀도에 저장, MORNING·QUANT 섀도·M6 이전 행은 NULL';

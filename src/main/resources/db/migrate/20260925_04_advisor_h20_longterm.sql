-- =============================================
-- 2026-09-25 advisor 주간 20거래일 판단(M7, ADVISE_H20·advice-h20-v1) 증분 (기존 운영 DB 전용, 재실행 안전)
-- 스키마 변경은 없다 — H20 판단은 기존 컬럼(advice_kind='H20', horizon_days=20, regime_json)에 저장된다. 이 파일은 주석만 갱신한다.
-- 적용 순서: 이 SQL(순서 무관) → 앱 배포 → H20 가중치 세트 준비(아래) → 금요일 20:10 스케줄 또는 수동 1회
--   H20 세트: POST /api/advisor/admin/jobs/IC_BACKFILL?horizon=20 (20일 IC 과거 구간) → 다음 WEEKLY_REVIEW 가 n_eff ≥ 24 이면 h=20 세트를 활성화.
--   세트가 없으면 ADVISE_H20 은 SKIPPED(skip.WEIGHTS) 로 닫힌다 — DAILY(h=5) 세트로 폴백하지 않는다.
--   수동: POST /api/advisor/admin/jobs/ADVISE_H20?baseDate=yyyy-MM-dd
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260925_04_advisor_h20_longterm.sql
-- 확인: SELECT horizon_days, is_active, n_eff FROM tb_advisor_weight_set WHERE horizon_days = 20 ORDER BY weight_set_id DESC LIMIT 3;
--       첫 금요일 뒤: SELECT base_date, variant, horizon_days, prompt_version, regime_json->'policy' FROM tb_advisor_advice
--                     WHERE advice_kind = 'H20' ORDER BY advice_id DESC LIMIT 4;   -- LIVE(advice-h20-v1)·QUANT_TOPN(quant) 각 1행, horizon_days 20
-- =============================================

COMMENT ON COLUMN tb_advisor_run.job_type          IS '잡 유형: ADVISE | SCORE | INTRADAY | MORNING_CHECK | MORNING_ADVISE | WEEKLY_REVIEW | IC_BACKFILL | ADVISE_ADHOC | ADVISE_H20 (AdvisorJobType)';
COMMENT ON COLUMN tb_advisor_advice.horizon_days       IS '결정 호라이즌(거래일): DAILY·MORNING·ADHOC 5, H20 20(M7), H60 60, H180 180 — ScoreJob 이 이 창으로 채점';
COMMENT ON COLUMN tb_advisor_advice.regime_json        IS '합성 국면 스냅샷 (M6, regime-policy-v1, 2026-09-25): {indexCode, tradeDate, trend, trendScore, vol LOW|NORMAL|HIGH|UNKNOWN, volPct(σ20 의 기준일 이전 최대 5년 분포 백분위), sigma20, volHistoryDays, policy{version,longMax,convictionCap,avoidMax}, themes[{code,members,rs5,rs20,rs60,breadth,strength,leaders}]}. DAILY·ADHOC·H20 LIVE 와 LLM 섀도에 저장(H20 의 policy 는 advisor.h20 픽 범위로 재계산), MORNING·QUANT 섀도·M6 이전 행은 NULL';

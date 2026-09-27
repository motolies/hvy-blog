-- =============================================
-- 2026-09-27 advisor 매수 전용 판단(advice-v9·advice-h20-v2·morning-v2, regime-policy-v2) 증분 (기존 운영 DB 전용, 재실행 안전)
-- 스키마 변경은 없다 — 주석만 갱신한다. tb_advisor_pick.direction 은 그대로 두어 과거 AVOID 행을 보존하고, 새 LLM 픽은 전부 LONG 이다.
-- 관망(0픽) 판단은 헤더만 있고 픽 행이 없다(FAILED 아님, run 메타 abstain=true). regime_json.policy 에서 avoidMax 가 빠진다(v1 행은 남는다).
-- 적용 순서: 이 SQL(순서 무관) → 앱 배포. 신규 설치는 db/advisor-schema.sql 로 충분하다.
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260927_01_advisor_long_only.sql
-- 확인: 배포 다음 날 SELECT a.base_date, a.advice_kind, a.variant, a.prompt_version, a.regime_json->'policy', COUNT(p.ticker) AS picks,
--              COUNT(*) FILTER (WHERE p.direction = 'AVOID') AS avoids
--       FROM tb_advisor_advice a LEFT JOIN tb_advisor_pick p ON p.advice_id = a.advice_id
--       WHERE a.base_date >= CURRENT_DATE - 3 GROUP BY 1, 2, 3, 4, 5 ORDER BY 1 DESC;   -- advice-v9 행은 avoids 0, 관망이면 picks 0
-- =============================================

COMMENT ON COLUMN tb_advisor_advice.regime_json        IS '합성 국면 스냅샷 (M6, 2026-09-25): {indexCode, tradeDate, trend, trendScore, vol LOW|NORMAL|HIGH|UNKNOWN, volPct(σ20 의 기준일 이전 최대 5년 분포 백분위), sigma20, volHistoryDays, policy{version,longMax,convictionCap}, themes[{code,members,rs5,rs20,rs60,breadth,strength,leaders}]}. policy.version 이 regime-policy-v1 인 행(2026-09-27 이전)은 policy.avoidMax 도 있다 — v2 는 매수 전용이라 없다. DAILY·ADHOC·H20 LIVE 와 LLM 섀도에 저장(H20 의 policy 는 advisor.h20 픽 상한으로 재계산), H60·H180 은 맥락 스냅샷(규칙 선택이라 policy NULL, M8), MORNING·QUANT 섀도·M6 이전 행은 NULL';
COMMENT ON COLUMN tb_advisor_pick.direction  IS 'LONG | AVOID. advice-v9·advice-h20-v2·morning-v2(2026-09-27, 매수 전용)부터 LLM 픽은 LONG 만 저장한다 — AVOID 는 그 전 행(과거 판단·전환기 아침 KEEP)에만 있다. 관망(0픽) 판단은 픽 행이 없다';

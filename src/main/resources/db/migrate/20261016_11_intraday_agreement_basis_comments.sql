-- 장중 점검 픽 일치 기준 전환 주석 (2026-10-16, MCP 2차 점검). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_11_intraday_agreement_basis_comments.sql
-- 매수 전용 판단은 지수 대비로 채점되는데 12:00 점검은 픽의 전일 대비 절대 등락으로 일치를 세어, 지수 하락 예측이 맞은 날 픽이 지수보다 덜 빠져도
-- "예측과 반대로 움직임" 이 나왔다. 이제 노트와 같은 지수 대비 초과로 세고(pick_json[].agreeBasis), 지수 방향 적중은 comment 에 따로 적는다. 과거 행은 소급하지 않는다.
-- 옛 행을 새 기준으로 다시 본 비교(분석용, 저장하지 않음):
--   SELECT c.check_id, c.agreement_ratio AS old_ratio,
--          AVG(CASE WHEN p->>'direction' = 'AVOID' THEN ((p->>'excess')::float8 <= 0)::int ELSE ((p->>'excess')::float8 > 0)::int END)
--            FILTER (WHERE p->>'excess' IS NOT NULL) AS excess_ratio
--   FROM tb_advisor_intraday_check c, jsonb_array_elements(c.pick_json) p GROUP BY 1, 2 ORDER BY 1 DESC LIMIT 20;
COMMENT ON COLUMN tb_advisor_intraday_check.index_json IS '지수 현재가 {"0001":{"price","changeRate","open","predicted","agree"}, ...}. agree 는 지수 방향 예측 적중(UP 양·DOWN 음·NEUTRAL |등락| < 0.3%)';
COMMENT ON COLUMN tb_advisor_intraday_check.pick_json IS '픽 [{"ticker","direction","price","changeRate","agree","agreeBasis","open","sinceOpen","excess","z","class"}|{"ticker","direction","error"}]. agreeBasis(OPEN|PREV_CLOSE, 2026-10-16~)는 agree 를 정한 지수 대비 초과 기준 — 키가 없는 옛 행의 agree 는 전일 대비 절대 등락';
COMMENT ON COLUMN tb_advisor_intraday_check.agreement_ratio IS '지수 대비 앞선 픽 비율(2026-10-16~): LONG 은 excess > 0, AVOID 는 ≤ 0 — excess 는 시가 기준(OPEN), 없으면 전일 종가 기준(PREV_CLOSE), 지수와 비교 불가한 픽은 분모 제외. 그 전 행은 전일 대비 상승 픽 비율';
COMMENT ON COLUMN tb_advisor_intraday_check.verdict IS 'ON_TRACK(agreement_ratio ≥ 0.6) | MIXED | OFF_TRACK(< 0.3). 지수 방향 적중은 index_json.*.agree';
COMMENT ON COLUMN tb_advisor_intraday_check.comment IS '규칙 기반 요약 — 픽의 지수 대비 판정 + 지수 방향 예측 적중 + 지수보다 약한 픽(2026-10-16~)';

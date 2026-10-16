-- tb_advisor_morning_check.detail_json 주석 갱신 (2026-10-16). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_05_morning_check_sigma_basis_comment.sql
-- 아침 점검 HOLD 임계가 종가 σ_1d(갭 σ 의 ≈2배)라 밤사이 갭과 비교하면 사실상 자동 적중(34/34, plan/mcp.md P1-12)이던 것을
-- 밤사이 갭 σ(advisor.morning.sigma-basis=GAP) 로 바꿨다. 점검 행의 index.*.sigmaBasis 로 전환 전후를 구분한다(키 없음 = CLOSE).
-- 채점(tb_advisor_call_score MORNING)은 저장된 threshold(band)를 쓰므로 과거 행은 그대로이고 KPI 비교는 전환일 전후를 나눠 읽는다.
COMMENT ON COLUMN tb_advisor_morning_check.detail_json IS '{us:{sym:{date,r1}}, index:{code:{beta,symbol,gapEst,threshold,sigma,sigmaBasis,predicted,verdict}}}. sigmaBasis GAP(2026-10-16 부터, 밤사이 갭 σ) | CLOSE(종가 σ_1d). 키가 없는 과거 행은 CLOSE';

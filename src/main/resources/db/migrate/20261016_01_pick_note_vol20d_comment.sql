-- tb_advisor_pick_note.z_score 주석 정정 (2026-10-16). 데이터·타입 변경 없음 (ddl-auto=validate 영향 없음).
-- 적용: psql -1 -f src/main/resources/db/migrate/20261016_01_pick_note_vol20d_comment.sql
-- 후보 feature_json 의 변동성 키는 "vol20d" 인데 IntradayCheckJob 이 "vol20" 을 읽어 z 가 항상 NULL → 전 픽 FLAT → 회고(REFLECT) 생략이던 결함을
-- 코드(CandidateScreeningService.FEATURE_VOL20D 상수)와 함께 고친다. 이전 노트(z NULL·FLAT)는 소급 재계산하지 않는다 — deviation/why/hypothesis 는
-- 12:00 장중 LLM 호출이라 재현할 수 없고, recentOutcomes 창(20거래일)이 지나면 올바른 노트로 채워진다.
COMMENT ON COLUMN tb_advisor_pick_note.z_score IS 'excess_rate / 반나절 σ. OPEN: vol20d × √(3/6.5), PREV_CLOSE: vol20d (후보 feature_json.vol20d, 없으면 NULL → FLAT)';

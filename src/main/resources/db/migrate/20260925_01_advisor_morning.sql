-- =============================================
-- 2026-09-25 advisor 아침 재판정(M4, morning-v1) 증분 (기존 운영 DB 전용, 재실행 안전)
-- 신규 설치는 db/advisor-schema.sql 로 충분하다. 기존 DB 는 CREATE TABLE IF NOT EXISTS 가 있는 테이블에 컬럼을 넣지 못하므로
-- 이 파일이 (1) tb_advisor_advice 에 parent_advice_id·diff_json, (2) tb_advisor_pick 에 action·action_reason 을 맨 뒤에 붙이고
-- (3) 종류·잡 유형·트리거·변형 컬럼 주석을 새 값으로 갱신한다. 본문은 advisor-schema.sql 과 같은 정의다.
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260925_01_advisor_morning.sql
-- 확인: SELECT column_name FROM information_schema.columns WHERE table_name = 'tb_advisor_advice' AND column_name IN ('parent_advice_id', 'diff_json');  -- 2행
--       SELECT column_name FROM information_schema.columns WHERE table_name = 'tb_advisor_pick' AND column_name IN ('action', 'action_reason');          -- 2행
-- 배포 뒤 scheduler.advisor-morning-advise.enabled(prod true) 로 평일 07:40 MORNING_ADVISE 가 돈다. 수동: POST /api/advisor/admin/jobs/MORNING_ADVISE
-- =============================================

-- (1) 아침 재판정 헤더: 원 저녁 판단 참조 + 저녁 대비 조치·트리거 메타
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS parent_advice_id BIGINT DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS diff_json        JSONB  DEFAULT NULL;

-- (2) 아침 재판정 픽의 조치 (MORNING 픽 = KEEP + ADD, DROP 은 헤더 diff_json 에만)
ALTER TABLE tb_advisor_pick ADD COLUMN IF NOT EXISTS action        VARCHAR(10)  DEFAULT NULL;
ALTER TABLE tb_advisor_pick ADD COLUMN IF NOT EXISTS action_reason VARCHAR(300) DEFAULT NULL;

-- (3) 주석
COMMENT ON COLUMN tb_advisor_run.job_type              IS '잡 유형: ADVISE | SCORE | INTRADAY | MORNING_CHECK | MORNING_ADVISE | WEEKLY_REVIEW | IC_BACKFILL | ADVISE_ADHOC (AdvisorJobType)';
COMMENT ON COLUMN tb_advisor_run.trigger_type          IS '트리거 출처: SCHEDULER | API | CHAT (AdvisorTriggerType)';
COMMENT ON COLUMN tb_advisor_advice.advice_kind        IS '판단 종류: DAILY 19:30 일일 | MORNING 07:40 아침 재판정(저녁과 같은 base_date·창) | H20 | H60 | H180 | ADHOC 채팅 수시 판단 (AdviceKind)';
COMMENT ON COLUMN tb_advisor_advice.variant            IS '변형: LIVE 발행본 | QUANT_TOPN 정량 top-N 섀도(LLM 없음) | QUANT_TOPN_BROAD 픽 유니버스 제한 없는 정량 섀도 | LLM_NOMEM 메모리 없는 LLM 섀도 | LLM_NONEWS 뉴스 없는 LLM 섀도';
COMMENT ON COLUMN tb_advisor_advice.parent_advice_id   IS '아침 재판정(MORNING)이 다시 본 원 저녁 판단(DAILY LIVE) advice_id. 그 밖의 종류는 NULL (M4, 2026-09-25)';
COMMENT ON COLUMN tb_advisor_advice.diff_json          IS '아침 재판정의 저녁 대비 조치 {parentAdviceId, keep:[{ticker,reason}], add:[…], drop:[{ticker,reason,direction,conviction}], triggers:{gap,sector,caution,any,…}, usDate}. 트리거는 호출 여부가 아니라 사후 분석(트리거일/비트리거일)용';
COMMENT ON COLUMN tb_advisor_pick.action               IS '아침 재판정(MORNING) 조치: KEEP | ADD (PickAction). DROP 은 픽에 없고 헤더 diff_json.drop 에만. 다른 종류는 NULL';
COMMENT ON COLUMN tb_advisor_pick.action_reason        IS '조치 사유 (모델 원문, 가드가 보충한 KEEP 은 그 사유)';

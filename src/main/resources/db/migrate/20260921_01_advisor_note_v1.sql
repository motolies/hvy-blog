-- =============================================
-- 2026-09-21 advisor advice-v6 · note-v1 릴리스 증분 (기존 운영 DB 전용, 재실행 안전)
-- 신규 설치는 db/advisor-schema.sql + db/advisor-seed.sql 로 충분하다. 기존 DB 는 CREATE TABLE IF NOT EXISTS 가 있는 테이블에
-- 컬럼을 넣지 못하므로 이 파일이 (1) 12:00 픽 노트 테이블 신설, (2) tb_advisor_advice.memory_json 컬럼 추가, (3) 주석 갱신,
-- (4) SEED 가중치 세트에 SECTOR_MOM_20D/60D 시드 2행 추가를 한 번에 한다. 본문은 advisor-schema.sql·advisor-seed.sql 과 같은 정의다.
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260921_01_advisor_note_v1.sql
-- 확인: SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'tb_advisor_%';   -- 15
--       SELECT column_name FROM information_schema.columns WHERE table_name = 'tb_advisor_advice' AND column_name = 'memory_json';
--       SELECT signal_code FROM tb_advisor_signal_weight w JOIN tb_advisor_weight_set s USING (weight_set_id)
--        WHERE s.source = 'SEED' AND signal_code LIKE 'SECTOR_MOM_%';                             -- 2행
-- 배포 뒤 POST /api/advisor/admin/jobs/IC_BACKFILL 1회(새 시그널 IC 사전 추정·새 BACKFILL 세트 활성화) — claudedocs/stock-advisor.md §10
-- =============================================

-- (1) 12:00 픽 노트 (15번째 advisor 테이블) — advisor-schema.sql 9절 원문
CREATE TABLE IF NOT EXISTS tb_advisor_pick_note
(
    note_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    advice_id        BIGINT           NOT NULL REFERENCES tb_advisor_advice (advice_id) ON DELETE CASCADE,
    check_id         BIGINT           NOT NULL REFERENCES tb_advisor_intraday_check (check_id) ON DELETE CASCADE,
    ticker           VARCHAR(10)      NOT NULL,
    base_date        DATE             NOT NULL,
    noted_at         TIMESTAMPTZ(6)   NOT NULL,
    direction        VARCHAR(10)      NOT NULL,
    conviction       DOUBLE PRECISION NOT NULL,
    open_price       NUMERIC(18,2)             DEFAULT NULL,
    current_price    NUMERIC(18,2)             DEFAULT NULL,
    change_rate      DOUBLE PRECISION          DEFAULT NULL,
    gap_rate         DOUBLE PRECISION          DEFAULT NULL,
    since_open_rate  DOUBLE PRECISION          DEFAULT NULL,
    bench_rate       DOUBLE PRECISION          DEFAULT NULL,
    excess_rate      DOUBLE PRECISION          DEFAULT NULL,
    z_score          DOUBLE PRECISION          DEFAULT NULL,
    note_class       VARCHAR(20)      NOT NULL,
    deviation        VARCHAR(300)              DEFAULT NULL,
    why              VARCHAR(600)              DEFAULT NULL,
    hypothesis       VARCHAR(300)              DEFAULT NULL,
    tags_json        JSONB                     DEFAULT NULL,
    status           VARCHAR(20)      NOT NULL DEFAULT 'OPEN',
    final_excess     DOUBLE PRECISION          DEFAULT NULL,
    finalized_at     TIMESTAMPTZ(6)            DEFAULT NULL,
    model            VARCHAR(80)               DEFAULT NULL,
    run_id           BIGINT                    DEFAULT NULL,
    created_at       TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_advisor_pick_note UNIQUE (check_id, ticker)
);

COMMENT ON TABLE  tb_advisor_pick_note                 IS '12:00 픽 노트(오답노트, note-v1). 점검 1회 = 픽마다 1행, append-only. 12:00 값은 여기만 — 픽·채점·IC·교훈 SQL 참조 금지';
COMMENT ON COLUMN tb_advisor_pick_note.note_id         IS '노트 식별자';
COMMENT ON COLUMN tb_advisor_pick_note.advice_id       IS '점검 대상 판단 (직전 영업일 LIVE)';
COMMENT ON COLUMN tb_advisor_pick_note.check_id        IS '원천 장중 점검 (tb_advisor_intraday_check)';
COMMENT ON COLUMN tb_advisor_pick_note.ticker          IS '단축 종목코드 (픽)';
COMMENT ON COLUMN tb_advisor_pick_note.base_date       IS '판단 기준일 (점검일 = 다음 영업일 = 픽 진입일)';
COMMENT ON COLUMN tb_advisor_pick_note.noted_at        IS '관측 시각 (KST 11:30~12:30 밖이면 tags_json.offHours=true — 장외 점검은 현재가=종가라 반나절 해석이 깨진다)';
COMMENT ON COLUMN tb_advisor_pick_note.direction       IS 'LONG | AVOID (픽 그대로)';
COMMENT ON COLUMN tb_advisor_pick_note.conviction      IS '판단 시 확신도 (픽 그대로)';
COMMENT ON COLUMN tb_advisor_pick_note.open_price      IS '당일 시가 (KIS stck_oprc, 진입가 근사)';
COMMENT ON COLUMN tb_advisor_pick_note.current_price   IS '관측 시각 현재가 (KIS stck_prpr)';
COMMENT ON COLUMN tb_advisor_pick_note.change_rate     IS '전일 대비율 % (KIS prdy_ctrt 그대로, 예: 1.2)';
COMMENT ON COLUMN tb_advisor_pick_note.gap_rate        IS '시가/기준가(stck_sdpr, 권리락 반영) − 1 (소수, 기록 전용 — MORNING 축)';
COMMENT ON COLUMN tb_advisor_pick_note.since_open_rate IS '현재가/시가 − 1 (소수) — 진입가 대비 1차 지표';
COMMENT ON COLUMN tb_advisor_pick_note.bench_rate      IS '소속 지수(bench_index_code) 전일 대비율 % (KIS bstp_nmix_prdy_ctrt)';
COMMENT ON COLUMN tb_advisor_pick_note.excess_rate     IS '지수 대비 초과 (소수). tags_json.excessBasis 가 OPEN 이면 sinceOpen − 지수 sinceOpen, PREV_CLOSE 면 (change_rate − bench_rate)/100 폴백';
COMMENT ON COLUMN tb_advisor_pick_note.z_score         IS 'excess_rate / 반나절 σ. OPEN: vol20 × √(3/6.5), PREV_CLOSE: vol20 (후보 feature_json.vol20, 없으면 NULL → FLAT)';
COMMENT ON COLUMN tb_advisor_pick_note.note_class      IS 'FLAT | ON_TRACK | MARKET_DRAG | IDIOSYNCRATIC | OVERSHOOT (PickNoteClass, 결정론 — AVOID 는 부호 반전)';
COMMENT ON COLUMN tb_advisor_pick_note.deviation       IS '회고: 얼마나·어떻게 (≤120자 목표, FLAT·LLM 실패는 NULL)';
COMMENT ON COLUMN tb_advisor_pick_note.why             IS '회고: thesis 의 어떤 가정이 흔들렸는지·risk 첫 신호 발동 여부 (≤200자 목표, 입력 숫자만 인용)';
COMMENT ON COLUMN tb_advisor_pick_note.hypothesis      IS '회고: 종목·날짜 없는 일반화 가설 (≤120자 목표). 티커·종목명·날짜 언급은 가드가 NULL 로 바꾼다. 프롬프트 미주입';
COMMENT ON COLUMN tb_advisor_pick_note.tags_json       IS '{signals:[SignalCode], sector, regime, excessBasis:OPEN|PREV_CLOSE, offHours:bool, benchCode, benchSinceOpen, secCons:1|0|null(후보 secRs5/20/60 → AdvicePromptBuilder.secCons)}';
COMMENT ON COLUMN tb_advisor_pick_note.status          IS 'OPEN 미확정 | CONFIRMED 12:00 초과 부호 == T+5 초과 부호 | REFUTED 불일치 (PickNoteStatus, T+5 잠정 채점 저장 직후 1회 확정)';
COMMENT ON COLUMN tb_advisor_pick_note.final_excess    IS 'T+5 채점 excess_ret (tb_advisor_candidate_score, 소수) — 반나절 신호가 T+5 를 맞혔는지의 정직한 기록';
COMMENT ON COLUMN tb_advisor_pick_note.finalized_at    IS '확정 시각 (recentOutcomes 는 finalized_at ≤ 기준일 20:00 KST 만 — bitemporal)';
COMMENT ON COLUMN tb_advisor_pick_note.model           IS '회고 모델 ID (assist). 회고가 없으면 NULL';
COMMENT ON COLUMN tb_advisor_pick_note.run_id          IS '점검 run';
COMMENT ON COLUMN tb_advisor_pick_note.created_at      IS '생성일시';

CREATE INDEX IF NOT EXISTS idx_advisor_pick_note_final ON tb_advisor_pick_note (finalized_at, base_date);
CREATE INDEX IF NOT EXISTS idx_advisor_pick_note_advice ON tb_advisor_pick_note (advice_id, ticker, noted_at);

-- (2) 프롬프트에 실린 메모리 요약 (NOMEM 섀도 창 시작점 판정용)
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS memory_json JSONB DEFAULT NULL;
COMMENT ON COLUMN tb_advisor_advice.memory_json        IS '프롬프트에 실린 메모리 요약 {recentOutcomes 행수, lessons [id], scoreboard bool} (note-v1, 2026-09-21). 하나도 실리지 않은 판단·LLM_NOMEM 섀도는 NULL — LIVE 의 MIN(base_date) WHERE NOT NULL 이 NOMEM 섀도 창 시작점';

-- (3) 장중 점검 테이블 주석 — 노트의 원천이며 학습 SQL 참조 금지
COMMENT ON TABLE  tb_advisor_intraday_check                 IS '장중 점검 — 보고 전용 + 픽 노트(tb_advisor_pick_note)의 원천. 특징·IC·채점·교훈 SQL 은 참조 금지(12:00 정보 소급 금지)';

-- (4) SEED 가중치 세트에 섹터 기간 모멘텀 시그널 2행 (advisor-seed.sql 과 같은 값, 이미 있으면 무시)
INSERT INTO tb_advisor_signal_weight (weight_set_id, signal_code, base_weight, multiplier, weight, enabled, note)
SELECT s.weight_set_id, v.code, v.base, 1.0, v.base, v.base > 0, v.note
FROM tb_advisor_weight_set s,
     (VALUES ('SECTOR_MOM_20D',  0.05, '섹터(업종 지수) 20일 시장 대비 초과 (advice-v6, 2026-09-21)'),
             ('SECTOR_MOM_60D',  0.05, '섹터(업종 지수) 60일 시장 대비 초과 (advice-v6, 2026-09-21)')) AS v(code, base, note)
WHERE s.source = 'SEED'
ON CONFLICT (weight_set_id, signal_code) DO NOTHING;

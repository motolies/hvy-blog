-- =============================================
-- AI 시장 판단(advisor) 모듈 테이블 DDL
-- PostgreSQL
-- =============================================
-- 이 프로젝트에는 Flyway 가 없다. 배포 전에 psql 로 수기 적용하고 schema-postgres.sql 과 동기화한다(AdvisorSchemaSyncTest).
-- stock 모듈(tb_stock_*, 22개·다른 접두 0 을 StockDailyPriceWriterPgTest 가 강제)과 분리하기 위해 접두는 tb_advisor_ 이고
-- FK 는 advisor 안에서만 건다 — tb_stock_* 로 FK 를 걸면 수집 재적재·상폐 정리가 판단 이력을 끌고 죽는다. ticker 는 값 참조.
-- 시각 컬럼은 TIMESTAMPTZ(6), 거래일은 KST 영업일 기준 DATE, 가격은 NUMERIC, 파생 지표·수익률은 DOUBLE PRECISION.
-- 설계 원칙(2026-09-13): 픽 레코드 동결(특징·가중치 세트·모델·프롬프트 해시), 수익률은 채점 시 수정주가 뷰로 재계산,
-- 가중치는 전 유니버스 rank-IC 로 학습, 픽 채점은 LLM 부가가치(픽 − 후보군)만 측정.
-- =============================================


-- =============================================
-- 1. 실행 이력 (JPA 엔티티 AdvisorRun)
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_run
(
    run_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_type          VARCHAR(30)    NOT NULL,
    trigger_type      VARCHAR(20)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    base_date         DATE                    DEFAULT NULL,
    model             VARCHAR(80)             DEFAULT NULL,
    prompt_version    VARCHAR(40)             DEFAULT NULL,
    llm_calls         INTEGER        NOT NULL DEFAULT 0,
    prompt_tokens     INTEGER        NOT NULL DEFAULT 0,
    completion_tokens INTEGER        NOT NULL DEFAULT 0,
    reasoning_tokens  INTEGER        NOT NULL DEFAULT 0,
    cached_tokens     INTEGER        NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(12,6)  NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    finished_at       TIMESTAMPTZ(6)          DEFAULT NULL,
    duration_ms       BIGINT                  DEFAULT NULL,
    error_message     TEXT                    DEFAULT NULL,
    metadata_json     JSONB                   DEFAULT NULL,
    created_at        TIMESTAMPTZ(6) NOT NULL,
    created_by        VARCHAR(255)            DEFAULT NULL,
    updated_at        TIMESTAMPTZ(6) NOT NULL,
    updated_by        VARCHAR(255)            DEFAULT NULL
);

COMMENT ON TABLE  tb_advisor_run                   IS 'AI 시장 판단 잡 실행 이력';
COMMENT ON COLUMN tb_advisor_run.run_id            IS '실행 식별자';
COMMENT ON COLUMN tb_advisor_run.job_type          IS '잡 유형: ADVISE | SCORE | INTRADAY | WEEKLY_REVIEW | IC_BACKFILL (AdvisorJobType)';
COMMENT ON COLUMN tb_advisor_run.trigger_type      IS '트리거 출처: SCHEDULER | API';
COMMENT ON COLUMN tb_advisor_run.status            IS '상태: RUNNING | SUCCESS | PARTIAL | FAILED | SKIPPED | CANCELED(관리자 취소, 2026-09-13)';
COMMENT ON COLUMN tb_advisor_run.base_date         IS '판단 기준 거래일 (ADVISE·INTRADAY) 또는 채점 기준일';
COMMENT ON COLUMN tb_advisor_run.model             IS '판단에 쓴 모델 ID';
COMMENT ON COLUMN tb_advisor_run.prompt_version    IS '프롬프트 리소스 버전 (advice-v1 등)';
COMMENT ON COLUMN tb_advisor_run.llm_calls         IS 'LLM 호출 횟수 (LIVE + 섀도)';
COMMENT ON COLUMN tb_advisor_run.prompt_tokens     IS '입력 토큰 합계';
COMMENT ON COLUMN tb_advisor_run.completion_tokens IS '출력 토큰 합계 (추론 토큰 포함)';
COMMENT ON COLUMN tb_advisor_run.reasoning_tokens  IS '추론 토큰 합계 (추론 모델만)';
COMMENT ON COLUMN tb_advisor_run.cached_tokens     IS '캐시 적중 입력 토큰 합계';
COMMENT ON COLUMN tb_advisor_run.cost_usd          IS 'advisor.cost 단가로 계산한 비용 (단가 0 이면 0)';
COMMENT ON COLUMN tb_advisor_run.started_at        IS '실행 시작 시각';
COMMENT ON COLUMN tb_advisor_run.finished_at       IS '실행 종료 시각';
COMMENT ON COLUMN tb_advisor_run.duration_ms       IS '소요 시간 (ms)';
COMMENT ON COLUMN tb_advisor_run.error_message     IS '실패 시 오류 메시지';
COMMENT ON COLUMN tb_advisor_run.metadata_json     IS '단계 결과·가드 카운터·경고 등 부가 정보';
COMMENT ON COLUMN tb_advisor_run.created_at        IS '생성일시';
COMMENT ON COLUMN tb_advisor_run.created_by        IS '생성자';
COMMENT ON COLUMN tb_advisor_run.updated_at        IS '수정일시';
COMMENT ON COLUMN tb_advisor_run.updated_by        IS '수정자';

-- 같은 잡은 동시에 하나만 RUNNING (중복 실행 차단의 실제 장치. ShedLock 은 스케줄 경로만 막는다)
CREATE UNIQUE INDEX IF NOT EXISTS uk_advisor_run_running ON tb_advisor_run (job_type) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_advisor_run_started ON tb_advisor_run (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_advisor_run_job_base ON tb_advisor_run (job_type, base_date DESC);


-- =============================================
-- 2. 시그널 가중치 세트 (이력 = 세트 행, 현재값 = is_active 인 세트 1개)
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_weight_set
(
    weight_set_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    as_of         DATE           NOT NULL,
    window_days   INTEGER        NOT NULL DEFAULT 0,
    n_eff         DOUBLE PRECISION NOT NULL DEFAULT 0,
    source        VARCHAR(20)    NOT NULL,
    is_active     BOOLEAN        NOT NULL DEFAULT FALSE,
    reason        VARCHAR(500)            DEFAULT NULL,
    run_id        BIGINT                  DEFAULT NULL,
    created_at    TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  tb_advisor_weight_set               IS '정량 시그널 가중치 세트. 픽은 그날 쓴 세트 id 를 참조해 재현 가능';
COMMENT ON COLUMN tb_advisor_weight_set.weight_set_id IS '세트 식별자';
COMMENT ON COLUMN tb_advisor_weight_set.as_of         IS '산출 기준일 (IC 창의 마지막 영업일)';
COMMENT ON COLUMN tb_advisor_weight_set.window_days   IS 'IC 집계 창(영업일). 시드는 0';
COMMENT ON COLUMN tb_advisor_weight_set.n_eff         IS '5일 겹침 보정 유효 표본 수 = 창/호라이즌';
COMMENT ON COLUMN tb_advisor_weight_set.source        IS '출처: SEED 초기값 | BACKFILL 사전 추정 | WEEKLY 주간 갱신 | MANUAL 수동 활성';
COMMENT ON COLUMN tb_advisor_weight_set.is_active     IS '스크리닝이 현재 쓰는 세트 (하나만 TRUE)';
COMMENT ON COLUMN tb_advisor_weight_set.reason        IS '갱신 사유 요약';
COMMENT ON COLUMN tb_advisor_weight_set.run_id        IS '산출한 run';
COMMENT ON COLUMN tb_advisor_weight_set.created_at    IS '생성일시';

CREATE UNIQUE INDEX IF NOT EXISTS uk_advisor_weight_set_active ON tb_advisor_weight_set (is_active) WHERE is_active;

CREATE TABLE IF NOT EXISTS tb_advisor_signal_weight
(
    weight_set_id BIGINT           NOT NULL REFERENCES tb_advisor_weight_set (weight_set_id) ON DELETE CASCADE,
    signal_code   VARCHAR(30)      NOT NULL,
    base_weight   DOUBLE PRECISION NOT NULL,
    multiplier    DOUBLE PRECISION NOT NULL DEFAULT 1,
    weight        DOUBLE PRECISION NOT NULL,
    enabled       BOOLEAN          NOT NULL DEFAULT TRUE,
    ic_mean       DOUBLE PRECISION          DEFAULT NULL,
    ic_se         DOUBLE PRECISION          DEFAULT NULL,
    t_stat        DOUBLE PRECISION          DEFAULT NULL,
    n_days        INTEGER                   DEFAULT NULL,
    flagged       BOOLEAN          NOT NULL DEFAULT FALSE,
    note          VARCHAR(300)              DEFAULT NULL,
    CONSTRAINT pk_advisor_signal_weight PRIMARY KEY (weight_set_id, signal_code)
);

COMMENT ON TABLE  tb_advisor_signal_weight               IS '세트 안 시그널별 가중치 (SignalCode)';
COMMENT ON COLUMN tb_advisor_signal_weight.weight_set_id IS '세트 식별자';
COMMENT ON COLUMN tb_advisor_signal_weight.signal_code   IS '시그널 코드 (SignalCode enum, code==상수명)';
COMMENT ON COLUMN tb_advisor_signal_weight.base_weight   IS '사전 가중치 (설계값, 합 1.0)';
COMMENT ON COLUMN tb_advisor_signal_weight.multiplier    IS 'IC 축소 추정 배수 clip[0.5, 2.0]. 시드·밸류 시그널은 1.0';
COMMENT ON COLUMN tb_advisor_signal_weight.weight        IS '실제 적용 가중치 = base × multiplier 를 Σbase 로 재정규화한 값';
COMMENT ON COLUMN tb_advisor_signal_weight.enabled       IS '스크리닝 포함 여부 (false 면 점수에서 제외)';
COMMENT ON COLUMN tb_advisor_signal_weight.ic_mean       IS '창 평균 rank-IC';
COMMENT ON COLUMN tb_advisor_signal_weight.ic_se         IS '표준오차 = std / √n_eff';
COMMENT ON COLUMN tb_advisor_signal_weight.t_stat        IS 'ic_mean / ic_se';
COMMENT ON COLUMN tb_advisor_signal_weight.n_days        IS 'IC 를 계산한 영업일 수';
COMMENT ON COLUMN tb_advisor_signal_weight.flagged       IS 'IC 가 음수(부호 반전 없이 하한 적용) 등 검토 필요 표시';
COMMENT ON COLUMN tb_advisor_signal_weight.note          IS '비고';


-- =============================================
-- 3. 추천 헤더 · 후보 · 픽
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_advice
(
    advice_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id             BIGINT         NOT NULL REFERENCES tb_advisor_run (run_id),
    base_date          DATE           NOT NULL,
    advice_kind        VARCHAR(20)    NOT NULL DEFAULT 'DAILY',
    variant            VARCHAR(20)    NOT NULL DEFAULT 'LIVE',
    horizon_days       SMALLINT       NOT NULL DEFAULT 5,
    regime_code        VARCHAR(20)             DEFAULT NULL,
    kospi_dir          VARCHAR(10)             DEFAULT NULL,
    kosdaq_dir         VARCHAR(10)             DEFAULT NULL,
    p_up               DOUBLE PRECISION        DEFAULT NULL,
    regime_rationale   VARCHAR(1000)           DEFAULT NULL,
    leading_sectors    JSONB                   DEFAULT NULL,
    summary            VARCHAR(1000)           DEFAULT NULL,
    trend_kospi        VARCHAR(20)             DEFAULT NULL,
    trend_kosdaq       VARCHAR(20)             DEFAULT NULL,
    trend_json         JSONB                   DEFAULT NULL,
    outlook_json       JSONB                   DEFAULT NULL,
    data_as_of_json    JSONB                   DEFAULT NULL,
    entry_date         DATE                    DEFAULT NULL,
    exit_date          DATE                    DEFAULT NULL,
    news_ids           JSONB                   DEFAULT NULL,
    prompt_version     VARCHAR(40)             DEFAULT NULL,
    model              VARCHAR(80)             DEFAULT NULL,
    system_fingerprint VARCHAR(80)             DEFAULT NULL,
    weight_set_id      BIGINT                  DEFAULT NULL REFERENCES tb_advisor_weight_set (weight_set_id),
    active_lesson_ids  JSONB                   DEFAULT NULL,
    data_quality       VARCHAR(20)    NOT NULL DEFAULT 'OK',
    guard_json         JSONB                   DEFAULT NULL,
    published_at       TIMESTAMPTZ(6)          DEFAULT NULL,
    created_at         TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_advisor_advice UNIQUE (base_date, advice_kind, variant)
);

COMMENT ON TABLE  tb_advisor_advice                    IS '일일 판단 헤더 (시장 국면·주도 섹터). 종목은 tb_advisor_pick';
COMMENT ON COLUMN tb_advisor_advice.advice_id          IS '판단 식별자';
COMMENT ON COLUMN tb_advisor_advice.run_id             IS '생성한 run';
COMMENT ON COLUMN tb_advisor_advice.base_date          IS '판단 기준 거래일 (특징은 이 날짜 이하만 사용)';
COMMENT ON COLUMN tb_advisor_advice.advice_kind        IS '판단 종류: DAILY (확장 여지)';
COMMENT ON COLUMN tb_advisor_advice.variant            IS '변형: LIVE 발행본 | QUANT_TOPN 정량 top-N 섀도(LLM 없음) | LLM_NOMEM 메모리 없는 LLM 섀도';
COMMENT ON COLUMN tb_advisor_advice.horizon_days       IS '결정 호라이즌(거래일)';
COMMENT ON COLUMN tb_advisor_advice.regime_code        IS '시장 국면: RISK_ON | NEUTRAL | RISK_OFF';
COMMENT ON COLUMN tb_advisor_advice.kospi_dir          IS 'KOSPI 5일 방향 예측: UP | NEUTRAL | DOWN';
COMMENT ON COLUMN tb_advisor_advice.kosdaq_dir         IS 'KOSDAQ 5일 방향 예측';
COMMENT ON COLUMN tb_advisor_advice.p_up               IS '국면 예측 신뢰도 (이산 0.55~0.9)';
COMMENT ON COLUMN tb_advisor_advice.regime_rationale   IS '국면 근거';
COMMENT ON COLUMN tb_advisor_advice.leading_sectors    IS '주도 섹터 [{code,name,reason}]';
COMMENT ON COLUMN tb_advisor_advice.summary            IS '총평 (300자)';
COMMENT ON COLUMN tb_advisor_advice.trend_kospi        IS 'KOSPI 규칙 기반 중기 추세: BULL | SIDEWAYS | BEAR (LLM 이 아니라 TrendSql 이 확정, advice-v2)';
COMMENT ON COLUMN tb_advisor_advice.trend_kosdaq       IS 'KOSDAQ 규칙 기반 중기 추세';
COMMENT ON COLUMN tb_advisor_advice.trend_json         IS '추세 상세 [{indexCode,code,score,components,since,days,ma20,ma60,ma120,breadth,base}]';
COMMENT ON COLUMN tb_advisor_advice.outlook_json       IS 'LLM 추세 지속 전망 [{indexCode,persist,confidence,invalidation}] — h=20 패스에서 TREND·TREND_INV 로 채점';
COMMENT ON COLUMN tb_advisor_advice.data_as_of_json    IS '입력 관측 기준일 {domestic,flow,sector,global,globalAgeTradingDays}';
COMMENT ON COLUMN tb_advisor_advice.entry_date         IS '적용 진입일(예정) = 기준일 다음 영업일 시가. 실제는 채점 시 캘린더로 재확정';
COMMENT ON COLUMN tb_advisor_advice.exit_date          IS '적용 청산일(예정) = horizon 번째 영업일 종가';
COMMENT ON COLUMN tb_advisor_advice.news_ids           IS '프롬프트에 실린 헤드라인 id 목록 ["N1",…] (advice-v4). 비어 있으면 뉴스 없이 판단';
COMMENT ON COLUMN tb_advisor_advice.prompt_version     IS '프롬프트 버전';
COMMENT ON COLUMN tb_advisor_advice.model              IS '모델 ID';
COMMENT ON COLUMN tb_advisor_advice.system_fingerprint IS 'OpenAI system_fingerprint (재현성 추적)';
COMMENT ON COLUMN tb_advisor_advice.weight_set_id      IS '스크리닝에 쓴 가중치 세트';
COMMENT ON COLUMN tb_advisor_advice.active_lesson_ids  IS '프롬프트에 넣은 활성 교훈 id 목록';
COMMENT ON COLUMN tb_advisor_advice.data_quality       IS '입력 품질: OK | DEGRADED (DAILY 단계 결손일 — 학습에서 제외)';
COMMENT ON COLUMN tb_advisor_advice.guard_json         IS 'AdviceGuard 가 제거·보정한 내역';
COMMENT ON COLUMN tb_advisor_advice.published_at       IS 'Slack 발행 시각 (NULL = 미발행)';
COMMENT ON COLUMN tb_advisor_advice.created_at         IS '생성일시';

CREATE INDEX IF NOT EXISTS idx_advisor_advice_run ON tb_advisor_advice (run_id);

CREATE TABLE IF NOT EXISTS tb_advisor_candidate
(
    advice_id          BIGINT           NOT NULL REFERENCES tb_advisor_advice (advice_id) ON DELETE CASCADE,
    ticker             VARCHAR(10)      NOT NULL,
    quant_rank         SMALLINT         NOT NULL,
    quant_score        DOUBLE PRECISION NOT NULL,
    stock_name         VARCHAR(100)              DEFAULT NULL,
    market_type        VARCHAR(10)      NOT NULL,
    bench_index_code   VARCHAR(20)      NOT NULL,
    sector_code        VARCHAR(20)               DEFAULT NULL,
    sector_name        VARCHAR(100)              DEFAULT NULL,
    signal_json        JSONB            NOT NULL,
    feature_json       JSONB                     DEFAULT NULL,
    applied_lesson_ids JSONB                     DEFAULT NULL,
    ref_raw_close      NUMERIC(18,2)             DEFAULT NULL,
    ref_adj_close      DOUBLE PRECISION          DEFAULT NULL,
    CONSTRAINT pk_advisor_candidate PRIMARY KEY (advice_id, ticker)
);

COMMENT ON TABLE  tb_advisor_candidate                    IS '정량 스크리닝 후보(≤30) 동결 스냅샷. 픽 채점의 대조군이자 재현성의 근거';
COMMENT ON COLUMN tb_advisor_candidate.advice_id          IS '판단 식별자';
COMMENT ON COLUMN tb_advisor_candidate.ticker             IS '단축 종목코드';
COMMENT ON COLUMN tb_advisor_candidate.quant_rank         IS '종합 점수 순위 (1 = 최고)';
COMMENT ON COLUMN tb_advisor_candidate.quant_score        IS '종합 점수 [-1, 1]';
COMMENT ON COLUMN tb_advisor_candidate.stock_name         IS '종목명 (그 시점)';
COMMENT ON COLUMN tb_advisor_candidate.market_type        IS 'KOSPI | KOSDAQ';
COMMENT ON COLUMN tb_advisor_candidate.bench_index_code   IS '채점 벤치마크 지수: 0001 | 1001';
COMMENT ON COLUMN tb_advisor_candidate.sector_code        IS 'KRX 중분류 섹터 코드 (그 시점 매핑)';
COMMENT ON COLUMN tb_advisor_candidate.sector_name        IS '섹터명';
COMMENT ON COLUMN tb_advisor_candidate.signal_json        IS '시그널 스냅샷 {code:{pct,w,raw}} — 가중치가 바뀌어도 그날 값이 남는다';
COMMENT ON COLUMN tb_advisor_candidate.feature_json       IS 'LLM 에 준 특징 행 (ret_20d 등, 투자자 수급 잠정값 포함)';
COMMENT ON COLUMN tb_advisor_candidate.applied_lesson_ids IS '이 후보에 condition 이 참이었던 교훈 id 목록 (효과 상대 비교용)';
COMMENT ON COLUMN tb_advisor_candidate.ref_raw_close      IS '기준일 원주가 종가 (배당 수익률 분모)';
COMMENT ON COLUMN tb_advisor_candidate.ref_adj_close      IS '기준일 수정 종가 (참조용, 채점은 뷰 재조회)';

CREATE INDEX IF NOT EXISTS idx_advisor_candidate_ticker ON tb_advisor_candidate (ticker);

CREATE TABLE IF NOT EXISTS tb_advisor_pick
(
    advice_id  BIGINT           NOT NULL,
    ticker     VARCHAR(10)      NOT NULL,
    pick_rank  SMALLINT         NOT NULL,
    direction  VARCHAR(10)      NOT NULL,
    conviction DOUBLE PRECISION NOT NULL,
    thesis     VARCHAR(600)              DEFAULT NULL,
    risk_note  VARCHAR(600)              DEFAULT NULL,
    cited_json JSONB                     DEFAULT NULL,
    cited_news JSONB                     DEFAULT NULL,
    CONSTRAINT pk_advisor_pick PRIMARY KEY (advice_id, ticker),
    CONSTRAINT fk_advisor_pick_candidate FOREIGN KEY (advice_id, ticker)
        REFERENCES tb_advisor_candidate (advice_id, ticker) ON DELETE CASCADE
);

COMMENT ON TABLE  tb_advisor_pick            IS 'LLM(또는 섀도 규칙)이 후보 중 고른 종목. 반드시 후보 안에 있어야 한다(FK)';
COMMENT ON COLUMN tb_advisor_pick.advice_id  IS '판단 식별자';
COMMENT ON COLUMN tb_advisor_pick.ticker     IS '단축 종목코드';
COMMENT ON COLUMN tb_advisor_pick.pick_rank  IS '확신 내림차순 순위';
COMMENT ON COLUMN tb_advisor_pick.direction  IS 'LONG | AVOID';
COMMENT ON COLUMN tb_advisor_pick.conviction IS '확신도 (이산 0.55~0.9)';
COMMENT ON COLUMN tb_advisor_pick.thesis     IS '근거 (200자 목표)';
COMMENT ON COLUMN tb_advisor_pick.risk_note  IS '리스크';
COMMENT ON COLUMN tb_advisor_pick.cited_json IS '근거로 인용한 특징 [{name,value}] — 입력값과 대조해 검증';
COMMENT ON COLUMN tb_advisor_pick.cited_news IS '근거로 인용한 헤드라인 id ["N3",…] — 프롬프트에 실린 id 만 (advice-v4)';

CREATE INDEX IF NOT EXISTS idx_advisor_pick_ticker ON tb_advisor_pick (ticker);


-- =============================================
-- 4. 채점 (후보 전부 + 국면·섹터 콜)
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_candidate_score
(
    advice_id       BIGINT           NOT NULL,
    ticker          VARCHAR(10)      NOT NULL,
    horizon_days    SMALLINT         NOT NULL,
    stage           VARCHAR(20)      NOT NULL,
    status          VARCHAR(20)      NOT NULL,
    entry_date      DATE                      DEFAULT NULL,
    entry_price     DOUBLE PRECISION          DEFAULT NULL,
    exit_date       DATE                      DEFAULT NULL,
    exit_price      DOUBLE PRECISION          DEFAULT NULL,
    ret             DOUBLE PRECISION          DEFAULT NULL,
    dividend_ret    DOUBLE PRECISION NOT NULL DEFAULT 0,
    bench_ret       DOUBLE PRECISION          DEFAULT NULL,
    excess_ret      DOUBLE PRECISION          DEFAULT NULL,
    cost_adj_excess DOUBLE PRECISION          DEFAULT NULL,
    scored_at       TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_advisor_candidate_score PRIMARY KEY (advice_id, ticker, horizon_days),
    CONSTRAINT fk_advisor_candidate_score FOREIGN KEY (advice_id, ticker)
        REFERENCES tb_advisor_candidate (advice_id, ticker) ON DELETE CASCADE
);

COMMENT ON TABLE  tb_advisor_candidate_score                 IS '후보 종목 채점. 픽 = tb_advisor_pick 조인, 후보군 평균 = 전체 평균. h=5 만 학습·KPI';
COMMENT ON COLUMN tb_advisor_candidate_score.advice_id       IS '판단 식별자';
COMMENT ON COLUMN tb_advisor_candidate_score.ticker          IS '단축 종목코드';
COMMENT ON COLUMN tb_advisor_candidate_score.horizon_days    IS '호라이즌: 5 결정 | 1·20 진단';
COMMENT ON COLUMN tb_advisor_candidate_score.stage           IS 'PROVISIONAL 잠정(T+h 직후) | CONFIRMED 확정(이후 첫 WEEKLY 뒤 수정주가 재계산)';
COMMENT ON COLUMN tb_advisor_candidate_score.status          IS 'SCORED | MISSING 진입가 없음 | SUSPENDED 청산일 거래정지(마지막 종가) | DELISTED 상폐(정리매매 종가)';
COMMENT ON COLUMN tb_advisor_candidate_score.entry_date      IS '진입일 = 기준일 다음 영업일';
COMMENT ON COLUMN tb_advisor_candidate_score.entry_price     IS '진입가 = 진입일 수정 시가';
COMMENT ON COLUMN tb_advisor_candidate_score.exit_date       IS '청산일 = 기준일 + h 영업일';
COMMENT ON COLUMN tb_advisor_candidate_score.exit_price      IS '청산가 = 청산일 수정 종가 (정지·상폐면 마지막 종가)';
COMMENT ON COLUMN tb_advisor_candidate_score.ret             IS '총수익률 = exit/entry − 1 + dividend_ret';
COMMENT ON COLUMN tb_advisor_candidate_score.dividend_ret    IS '구간 내 배당락 현금배당 / 진입일 원주가 (계수가 없는 DIVIDEND 보정)';
COMMENT ON COLUMN tb_advisor_candidate_score.bench_ret       IS '벤치마크(소속 시장 지수) 수익률 = 지수 종가(exit)/지수 시가(entry) − 1';
COMMENT ON COLUMN tb_advisor_candidate_score.excess_ret      IS 'ret − bench_ret (학습·KPI 는 이 값)';
COMMENT ON COLUMN tb_advisor_candidate_score.cost_adj_excess IS 'excess_ret − 왕복 비용(advisor.scoring.cost-bps). 보고 전용';
COMMENT ON COLUMN tb_advisor_candidate_score.scored_at       IS '채점 시각';

CREATE INDEX IF NOT EXISTS idx_advisor_candidate_score_stage ON tb_advisor_candidate_score (stage, exit_date);

CREATE TABLE IF NOT EXISTS tb_advisor_call_score
(
    advice_id    BIGINT           NOT NULL REFERENCES tb_advisor_advice (advice_id) ON DELETE CASCADE,
    subject_type VARCHAR(10)      NOT NULL,
    subject_code VARCHAR(20)      NOT NULL,
    horizon_days SMALLINT         NOT NULL,
    stage        VARCHAR(20)      NOT NULL,
    status       VARCHAR(20)      NOT NULL,
    predicted    VARCHAR(20)      NOT NULL,
    p_up         DOUBLE PRECISION          DEFAULT NULL,
    base_value   DOUBLE PRECISION          DEFAULT NULL,
    exit_value   DOUBLE PRECISION          DEFAULT NULL,
    actual_ret   DOUBLE PRECISION          DEFAULT NULL,
    bench_ret    DOUBLE PRECISION          DEFAULT NULL,
    band         DOUBLE PRECISION          DEFAULT NULL,
    actual_dir   VARCHAR(20)               DEFAULT NULL,
    hit          BOOLEAN                   DEFAULT NULL,
    brier        DOUBLE PRECISION          DEFAULT NULL,
    event_date   DATE                      DEFAULT NULL,
    scored_at    TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_advisor_call_score PRIMARY KEY (advice_id, subject_type, subject_code, horizon_days)
);

COMMENT ON TABLE  tb_advisor_call_score              IS '국면(지수 방향)·주도 섹터·추세 지속·무효화 콜 채점';
COMMENT ON COLUMN tb_advisor_call_score.advice_id    IS '판단 식별자';
COMMENT ON COLUMN tb_advisor_call_score.subject_type IS 'INDEX (0001/1001 방향) | SECTOR (주도 섹터) | TREND (추세 지속 버킷, h=20) | TREND_INV (무효화 조기 신호, h=20)';
COMMENT ON COLUMN tb_advisor_call_score.subject_code IS '지수 코드 또는 섹터 코드';
COMMENT ON COLUMN tb_advisor_call_score.horizon_days IS '호라이즌 (INDEX·SECTOR 5 + 진단 1·20, TREND·TREND_INV 20)';
COMMENT ON COLUMN tb_advisor_call_score.stage        IS 'PROVISIONAL | CONFIRMED';
COMMENT ON COLUMN tb_advisor_call_score.status       IS 'SCORED | MISSING';
COMMENT ON COLUMN tb_advisor_call_score.predicted    IS 'INDEX: UP|NEUTRAL|DOWN, SECTOR: LEAD, TREND: WITHIN_5D|ABOUT_20D|BEYOND_20D, TREND_INV: BELOW_MA20|BELOW_MA60|ABOVE_MA20|ABOVE_MA60';
COMMENT ON COLUMN tb_advisor_call_score.p_up         IS 'INDEX 예측 신뢰도';
COMMENT ON COLUMN tb_advisor_call_score.base_value   IS '기준일 종가 (close-to-close)';
COMMENT ON COLUMN tb_advisor_call_score.exit_value   IS '청산일 종가';
COMMENT ON COLUMN tb_advisor_call_score.actual_ret   IS '실현 수익률 (지수 또는 섹터 지수)';
COMMENT ON COLUMN tb_advisor_call_score.bench_ret    IS 'SECTOR: 같은 구간 시장 지수 수익률 (INDEX 는 NULL)';
COMMENT ON COLUMN tb_advisor_call_score.band         IS 'INDEX: NEUTRAL 판정 밴드 = band-sigma × σ_5d';
COMMENT ON COLUMN tb_advisor_call_score.actual_dir   IS 'INDEX: |ret| < band 면 NEUTRAL, 아니면 부호. TREND: 실현 버킷';
COMMENT ON COLUMN tb_advisor_call_score.hit          IS 'INDEX·TREND: predicted == actual_dir, SECTOR: actual_ret − bench_ret > 0, TREND_INV: 전환·발동 일치(둘 다 없음 또는 ±허용일 안)';
COMMENT ON COLUMN tb_advisor_call_score.brier        IS 'INDEX: (p_up − 1[ret>0])² 부호 기준 | TREND: (p_up − 1[hit])² 적중 기준 — KPI 에서 섞지 않는다';
COMMENT ON COLUMN tb_advisor_call_score.event_date   IS 'TREND: 라벨 전환일 | TREND_INV: 무효화 조건 최초 충족일 (없으면 NULL)';
COMMENT ON COLUMN tb_advisor_call_score.scored_at    IS '채점 시각';


-- =============================================
-- 5. 시그널 일별 rank-IC (전 유니버스, 가중치 학습의 원천)
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_signal_ic_daily
(
    signal_code  VARCHAR(30)      NOT NULL,
    trade_date   DATE             NOT NULL,
    horizon_days SMALLINT         NOT NULL DEFAULT 5,
    rank_ic      DOUBLE PRECISION NOT NULL,
    n            INTEGER          NOT NULL,
    computed_at  TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_advisor_signal_ic_daily PRIMARY KEY (signal_code, trade_date)
);

COMMENT ON TABLE  tb_advisor_signal_ic_daily              IS '시그널별 일별 rank-IC = corr(rank(시그널 d), rank(초과수익 d→d+h)), 유니버스 전체';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.signal_code  IS '시그널 코드';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.trade_date   IS '시그널 기준일 d (d+h 가 확보된 뒤 계산)';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.horizon_days IS '호라이즌';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.rank_ic      IS 'Spearman 상관 (순위 Pearson)';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.n            IS '표본 종목 수';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.computed_at  IS '계산 시각';

CREATE INDEX IF NOT EXISTS idx_advisor_signal_ic_date ON tb_advisor_signal_ic_daily (trade_date);


-- =============================================
-- 6. 교훈 (프롬프트 메모리)
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_lesson
(
    lesson_id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status                  VARCHAR(20)    NOT NULL,
    scope                   VARCHAR(20)    NOT NULL,
    condition_json          JSONB          NOT NULL,
    observation             VARCHAR(300)   NOT NULL,
    evidence_json           JSONB          NOT NULL,
    rule                    VARCHAR(300)   NOT NULL,
    lesson_text             VARCHAR(600)   NOT NULL,
    applied_count           INTEGER        NOT NULL DEFAULT 0,
    post_n_applied          INTEGER                 DEFAULT NULL,
    post_excess_applied     DOUBLE PRECISION        DEFAULT NULL,
    post_n_not_applied      INTEGER                 DEFAULT NULL,
    post_excess_not_applied DOUBLE PRECISION        DEFAULT NULL,
    activated_at            TIMESTAMPTZ(6)          DEFAULT NULL,
    retired_at              TIMESTAMPTZ(6)          DEFAULT NULL,
    retired_reason          VARCHAR(300)            DEFAULT NULL,
    model                   VARCHAR(80)             DEFAULT NULL,
    run_id                  BIGINT                  DEFAULT NULL,
    created_at              TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  tb_advisor_lesson                         IS '주간 검토가 만든 교훈. 기계 판정 condition + 통계 evidence 가 없으면 저장 거부, 티커 금지, 활성 ≤8';
COMMENT ON COLUMN tb_advisor_lesson.lesson_id               IS '교훈 식별자';
COMMENT ON COLUMN tb_advisor_lesson.status                  IS 'CANDIDATE | ACTIVE | RETIRED';
COMMENT ON COLUMN tb_advisor_lesson.scope                   IS 'SIGNAL | REGIME | SECTOR | CALIBRATION';
COMMENT ON COLUMN tb_advisor_lesson.condition_json          IS '적용 조건 술어 {regime, signal, op, threshold, sector} — 후보마다 기계 평가';
COMMENT ON COLUMN tb_advisor_lesson.observation             IS '관찰 (≤80자 목표)';
COMMENT ON COLUMN tb_advisor_lesson.evidence_json           IS '근거 통계 {n, from, to, excess, t}';
COMMENT ON COLUMN tb_advisor_lesson.rule                    IS '규칙 (신뢰도 조정만 허용, ≤80자 목표)';
COMMENT ON COLUMN tb_advisor_lesson.lesson_text             IS '프롬프트에 넣는 렌더링 텍스트 (≤200자 목표)';
COMMENT ON COLUMN tb_advisor_lesson.applied_count           IS 'condition 이 참이었던 후보 누적 수';
COMMENT ON COLUMN tb_advisor_lesson.post_n_applied          IS '활성 후 적용 픽 수';
COMMENT ON COLUMN tb_advisor_lesson.post_excess_applied     IS '활성 후 적용 픽 평균 초과수익';
COMMENT ON COLUMN tb_advisor_lesson.post_n_not_applied      IS '활성 후 비적용 픽 수';
COMMENT ON COLUMN tb_advisor_lesson.post_excess_not_applied IS '활성 후 비적용 픽 평균 초과수익';
COMMENT ON COLUMN tb_advisor_lesson.activated_at            IS '활성화 시각';
COMMENT ON COLUMN tb_advisor_lesson.retired_at              IS '폐기 시각';
COMMENT ON COLUMN tb_advisor_lesson.retired_reason          IS '폐기 사유';
COMMENT ON COLUMN tb_advisor_lesson.model                   IS '생성 모델';
COMMENT ON COLUMN tb_advisor_lesson.run_id                  IS '생성 run';
COMMENT ON COLUMN tb_advisor_lesson.created_at              IS '생성일시';

CREATE INDEX IF NOT EXISTS idx_advisor_lesson_status ON tb_advisor_lesson (status, created_at DESC);


-- =============================================
-- 7. 장중 점검 · 프롬프트 입력 스냅샷
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_intraday_check
(
    check_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    advice_id       BIGINT           NOT NULL REFERENCES tb_advisor_advice (advice_id) ON DELETE CASCADE,
    run_id          BIGINT                    DEFAULT NULL,
    checked_at      TIMESTAMPTZ(6)   NOT NULL,
    index_json      JSONB                     DEFAULT NULL,
    pick_json       JSONB                     DEFAULT NULL,
    agreement_ratio DOUBLE PRECISION          DEFAULT NULL,
    verdict         VARCHAR(20)      NOT NULL,
    comment         VARCHAR(600)              DEFAULT NULL,
    created_at      TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_advisor_intraday UNIQUE (advice_id, checked_at)
);

COMMENT ON TABLE  tb_advisor_intraday_check                 IS '장중 점검 (보고 전용 — 학습에 쓰지 않는다)';
COMMENT ON COLUMN tb_advisor_intraday_check.check_id        IS '점검 식별자';
COMMENT ON COLUMN tb_advisor_intraday_check.advice_id       IS '점검 대상 판단 (전 영업일 LIVE)';
COMMENT ON COLUMN tb_advisor_intraday_check.run_id          IS '점검 run';
COMMENT ON COLUMN tb_advisor_intraday_check.checked_at      IS '점검 시각';
COMMENT ON COLUMN tb_advisor_intraday_check.index_json      IS '지수 현재가 {"0001":{"price","changeRate","predicted","agree"}, ...}';
COMMENT ON COLUMN tb_advisor_intraday_check.pick_json       IS '픽 현재가 [{"ticker","price","changeRate","direction","agree"}]';
COMMENT ON COLUMN tb_advisor_intraday_check.agreement_ratio IS '예측 방향과 일치한 픽 비율';
COMMENT ON COLUMN tb_advisor_intraday_check.verdict         IS 'ON_TRACK | MIXED | OFF_TRACK';
COMMENT ON COLUMN tb_advisor_intraday_check.comment         IS '규칙 기반 요약';
COMMENT ON COLUMN tb_advisor_intraday_check.created_at      IS '생성일시';

CREATE TABLE IF NOT EXISTS tb_advisor_morning_check
(
    check_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    advice_id    BIGINT           NOT NULL REFERENCES tb_advisor_advice (advice_id) ON DELETE CASCADE,
    run_id       BIGINT                    DEFAULT NULL,
    base_date    DATE             NOT NULL,
    us_date      DATE             NOT NULL,
    gap_kospi    DOUBLE PRECISION          DEFAULT NULL,
    gap_kosdaq   DOUBLE PRECISION          DEFAULT NULL,
    verdict      VARCHAR(20)      NOT NULL,
    detail_json  JSONB                     DEFAULT NULL,
    published_at TIMESTAMPTZ(6)            DEFAULT NULL,
    created_at   TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_advisor_morning_check UNIQUE (advice_id)
);

COMMENT ON TABLE  tb_advisor_morning_check              IS '아침 해외 반영 점검 (07:30, 규칙 기반, LLM 미사용, advice-v3). 원 판단(tb_advisor_advice)은 수정하지 않는다';
COMMENT ON COLUMN tb_advisor_morning_check.check_id     IS '점검 식별자';
COMMENT ON COLUMN tb_advisor_morning_check.advice_id    IS '점검 대상 판단 (직전 영업일 LIVE)';
COMMENT ON COLUMN tb_advisor_morning_check.run_id       IS '점검 run';
COMMENT ON COLUMN tb_advisor_morning_check.base_date    IS '판단 기준일';
COMMENT ON COLUMN tb_advisor_morning_check.us_date      IS '반영한 미국 세션의 현지 거래일 (KST 새벽 마감, 정상이면 base_date 와 같다)';
COMMENT ON COLUMN tb_advisor_morning_check.gap_kospi    IS 'KOSPI 예상 갭 = β(주 심볼) × 미국 1일 수익률';
COMMENT ON COLUMN tb_advisor_morning_check.gap_kosdaq   IS 'KOSDAQ 예상 갭';
COMMENT ON COLUMN tb_advisor_morning_check.verdict      IS 'REINFORCE 강화 | HOLD 유지 | CAUTION 주의 (지수별 판정 중 가장 심각한 것)';
COMMENT ON COLUMN tb_advisor_morning_check.detail_json  IS '{us:{sym:{date,r1}}, index:{code:{beta,symbol,gapEst,threshold,predicted,verdict}}}';
COMMENT ON COLUMN tb_advisor_morning_check.published_at IS 'Slack 발행 시각';
COMMENT ON COLUMN tb_advisor_morning_check.created_at   IS '생성일시';

CREATE TABLE IF NOT EXISTS tb_advisor_prompt_input
(
    run_id         BIGINT         NOT NULL REFERENCES tb_advisor_run (run_id) ON DELETE CASCADE,
    variant        VARCHAR(20)    NOT NULL DEFAULT 'LIVE',
    prompt_version VARCHAR(40)    NOT NULL,
    system_sha256  VARCHAR(64)    NOT NULL,
    user_payload   JSONB          NOT NULL,
    options_json   JSONB                   DEFAULT NULL,
    raw_output     JSONB                   DEFAULT NULL,
    created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_advisor_prompt_input PRIMARY KEY (run_id, variant)
);

COMMENT ON TABLE  tb_advisor_prompt_input                IS 'LLM 입력·출력 원문 스냅샷 (재현성). 보존 advisor.retention-days';
COMMENT ON COLUMN tb_advisor_prompt_input.run_id         IS 'run';
COMMENT ON COLUMN tb_advisor_prompt_input.variant        IS 'LIVE | LLM_NOMEM';
COMMENT ON COLUMN tb_advisor_prompt_input.prompt_version IS '프롬프트 버전';
COMMENT ON COLUMN tb_advisor_prompt_input.system_sha256  IS '시스템 프롬프트 SHA-256';
COMMENT ON COLUMN tb_advisor_prompt_input.user_payload   IS '사용자 메시지 JSON 원문';
COMMENT ON COLUMN tb_advisor_prompt_input.options_json   IS '모델·출력 상한·응답 스키마';
COMMENT ON COLUMN tb_advisor_prompt_input.raw_output     IS '가드 이전 원본 응답';
COMMENT ON COLUMN tb_advisor_prompt_input.created_at     IS '생성일시';

-- =============================================
-- 8. Slack 채팅 봇 감사 (chat-v1, 2026-09-13) — #hvy-advisor 질문 1건 = 1행. 예산 집계·비용 진단·사후 검토용
-- =============================================
CREATE TABLE IF NOT EXISTS tb_advisor_chat
(
    chat_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id          VARCHAR(64)    NOT NULL,
    channel_id        VARCHAR(32)    NOT NULL,
    thread_ts         VARCHAR(32)    NOT NULL,
    message_ts        VARCHAR(32)    NOT NULL,
    slack_user_id     VARCHAR(32)    NOT NULL,
    question          TEXT           NOT NULL,
    answer            TEXT                    DEFAULT NULL,
    status            VARCHAR(20)    NOT NULL,
    model             VARCHAR(80)             DEFAULT NULL,
    prompt_version    VARCHAR(40)             DEFAULT NULL,
    history_messages  INTEGER        NOT NULL DEFAULT 0,
    tool_calls        INTEGER        NOT NULL DEFAULT 0,
    tool_calls_json   JSONB                   DEFAULT NULL,
    prompt_tokens     INTEGER        NOT NULL DEFAULT 0,
    completion_tokens INTEGER        NOT NULL DEFAULT 0,
    reasoning_tokens  INTEGER        NOT NULL DEFAULT 0,
    cached_tokens     INTEGER        NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(12, 6) NOT NULL DEFAULT 0,
    data_as_of        DATE                    DEFAULT NULL,
    duration_ms       BIGINT                  DEFAULT NULL,
    error_message     TEXT                    DEFAULT NULL,
    created_at        TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  tb_advisor_chat                   IS 'Slack #hvy-advisor 채팅 봇 감사 — 질문 1건 = 1행 (chat-v1, 2026-09-13). 일일 토큰 예산·비용 진단·사후 검토에 쓴다';
COMMENT ON COLUMN tb_advisor_chat.chat_id           IS '식별자';
COMMENT ON COLUMN tb_advisor_chat.event_id          IS 'Slack event_id — 유니크. Redis 중복 제거(10분)가 비어도 재전송을 막는 2차 방어선';
COMMENT ON COLUMN tb_advisor_chat.channel_id        IS 'Slack 채널 ID (C…)';
COMMENT ON COLUMN tb_advisor_chat.thread_ts         IS '답글을 단 스레드 ts (댓글이면 원 스레드, 새 글이면 그 글의 ts)';
COMMENT ON COLUMN tb_advisor_chat.message_ts        IS '질문 메시지 ts';
COMMENT ON COLUMN tb_advisor_chat.slack_user_id     IS '질문한 Slack 사용자 ID (U…)';
COMMENT ON COLUMN tb_advisor_chat.question          IS '질문 원문';
COMMENT ON COLUMN tb_advisor_chat.answer            IS '답변 본문 (mrkdwn 변환 전 모델 출력)';
COMMENT ON COLUMN tb_advisor_chat.status            IS 'RUNNING → SUCCESS | FAILED | SKIPPED(예산·쿨다운 거부) — AdvisorStatus 재사용';
COMMENT ON COLUMN tb_advisor_chat.model             IS '응답 모델 ID';
COMMENT ON COLUMN tb_advisor_chat.prompt_version    IS '시스템 프롬프트 버전 (chat-v1 …)';
COMMENT ON COLUMN tb_advisor_chat.history_messages  IS '프롬프트에 넣은 스레드 이전 메시지 수 (입력 토큰 진단: 히스토리 vs 도구 결과)';
COMMENT ON COLUMN tb_advisor_chat.tool_calls        IS '도구 호출 횟수';
COMMENT ON COLUMN tb_advisor_chat.tool_calls_json   IS '호출한 도구 이름 목록 (순서대로) ["dataFreshness","resolveStock",…]';
COMMENT ON COLUMN tb_advisor_chat.prompt_tokens     IS '입력 토큰 (도구 루프 누적)';
COMMENT ON COLUMN tb_advisor_chat.completion_tokens IS '출력 토큰 (추론 토큰 포함, 누적)';
COMMENT ON COLUMN tb_advisor_chat.reasoning_tokens  IS '추론 토큰';
COMMENT ON COLUMN tb_advisor_chat.cached_tokens     IS '프롬프트 캐시 적중 입력 토큰';
COMMENT ON COLUMN tb_advisor_chat.cost_usd          IS 'advisor.cost 단가로 계산한 비용 (0 이면 미계산)';
COMMENT ON COLUMN tb_advisor_chat.data_as_of        IS '답변이 참조한 데이터 기준일 (도구 반환 asOf 중 가장 이른 값)';
COMMENT ON COLUMN tb_advisor_chat.duration_ms       IS '수신부터 답글까지 소요(ms)';
COMMENT ON COLUMN tb_advisor_chat.error_message     IS '실패 사유';
COMMENT ON COLUMN tb_advisor_chat.created_at        IS '생성일시';
COMMENT ON COLUMN tb_advisor_chat.updated_at        IS '수정일시';

CREATE UNIQUE INDEX IF NOT EXISTS uk_advisor_chat_event ON tb_advisor_chat (event_id);
CREATE INDEX IF NOT EXISTS idx_advisor_chat_created ON tb_advisor_chat (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_advisor_chat_user ON tb_advisor_chat (slack_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_advisor_chat_thread ON tb_advisor_chat (channel_id, thread_ts, created_at);

-- =============================================
-- 마이그레이션 (advice-v2, 2026-09-13). 신규 설치는 위 CREATE 본문에 이미 포함돼 있고, 기존 설치는 아래가 컬럼을 보탠다. 재실행 안전.
-- CREATE TABLE IF NOT EXISTS 는 있는 테이블에 컬럼을 넣지 않으므로 본문과 이 블록을 함께 고친다. varchar 확대는 메타데이터 변경이라 재작성 없음.
-- =============================================
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS trend_kospi     VARCHAR(20) DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS trend_kosdaq    VARCHAR(20) DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS trend_json      JSONB       DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS outlook_json    JSONB       DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS data_as_of_json JSONB       DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS entry_date      DATE        DEFAULT NULL;
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS exit_date       DATE        DEFAULT NULL;
ALTER TABLE tb_advisor_call_score ALTER COLUMN predicted  TYPE VARCHAR(20);
ALTER TABLE tb_advisor_call_score ALTER COLUMN actual_dir TYPE VARCHAR(20);
ALTER TABLE tb_advisor_call_score ADD COLUMN IF NOT EXISTS event_date DATE DEFAULT NULL;
-- advice-v4 (뉴스): 인용 헤드라인
ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS news_ids   JSONB DEFAULT NULL;
ALTER TABLE tb_advisor_pick   ADD COLUMN IF NOT EXISTS cited_news JSONB DEFAULT NULL;

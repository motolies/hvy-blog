-- =============================================
-- 2026-09-25 advisor 멀티 호라이즌 인프라(M5) 증분 (기존 운영 DB 전용, 재실행 안전)
-- 신규 설치는 db/advisor-schema.sql 로 충분하다. 기존 DB 에서 이 파일은
--   (1) tb_advisor_signal_ic_daily 의 PK 를 (signal_code, trade_date) → (signal_code, trade_date, horizon_days) 로 바꾸고
--       (horizon_days 는 최초 DDL 부터 있었다. 없는 DB 를 위해 ADD COLUMN IF NOT EXISTS 를 먼저 둔다)
--   (2) tb_advisor_weight_set 맨 뒤에 horizon_days 를 붙이고 활성 유니크를 전역 1개 → 호라이즌마다 1개로 바꾸며
--   (3) 두 테이블 주석을 갱신한다.
-- 기존 행은 전부 h=5 로 저장돼 있으므로(SignalIcService 가 advisor.horizon-days 하나로만 계산) 새 PK·유니크를 위반하지 않는다.
-- 적용 순서: 이 SQL → 앱 배포 → POST /api/advisor/admin/jobs/IC_BACKFILL (horizon 생략 = 학습 호라이즌 5·20 전부, 20 의 첫 가중치 세트 생성)
--           → 필요하면 POST /api/advisor/admin/jobs/IC_BACKFILL?horizon=60 · ?horizon=180 (모니터링 IC 과거 구간)
-- 적용: psql "$DATABASE_URL" -f src/main/resources/db/migrate/20260925_02_advisor_multi_horizon.sql
-- 확인: SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'pk_advisor_signal_ic_daily';   -- PRIMARY KEY (signal_code, trade_date, horizon_days)
--       SELECT indexdef FROM pg_indexes WHERE indexname LIKE 'uk_advisor_weight_set_active%';               -- 1행, (horizon_days) WHERE is_active
--       SELECT horizon_days, COUNT(*) FILTER (WHERE is_active) FROM tb_advisor_weight_set GROUP BY 1;       -- 5 → 1
-- =============================================

-- (1) IC: 호라이즌을 PK 에 넣는다. 배포 전 앱(ON CONFLICT (signal_code, trade_date))은 새 PK 와 맞는 제약이 없어 IC 저장이 실패하므로 앱 배포 직전에 적용한다
ALTER TABLE tb_advisor_signal_ic_daily ADD COLUMN IF NOT EXISTS horizon_days SMALLINT NOT NULL DEFAULT 5;
ALTER TABLE tb_advisor_signal_ic_daily DROP CONSTRAINT IF EXISTS pk_advisor_signal_ic_daily;
ALTER TABLE tb_advisor_signal_ic_daily ADD CONSTRAINT pk_advisor_signal_ic_daily PRIMARY KEY (signal_code, trade_date, horizon_days);

-- (2) 가중치 세트: 학습 호라이즌 컬럼(맨 뒤) + 호라이즌별 활성 유니크
ALTER TABLE tb_advisor_weight_set ADD COLUMN IF NOT EXISTS horizon_days SMALLINT NOT NULL DEFAULT 5;
DROP INDEX IF EXISTS uk_advisor_weight_set_active;
CREATE UNIQUE INDEX IF NOT EXISTS uk_advisor_weight_set_active_horizon ON tb_advisor_weight_set (horizon_days) WHERE is_active;

-- (3) 주석
COMMENT ON TABLE  tb_advisor_signal_ic_daily              IS '시그널별 일별 rank-IC = corr(rank(시그널 d), rank(초과수익 d→d+h)), 유니버스 전체. 호라이즌마다 별도 행(PK 에 horizon_days, M5)';
COMMENT ON COLUMN tb_advisor_signal_ic_daily.horizon_days IS '호라이즌 h (advisor.horizons 키: 5·20 학습, 60·180 모니터링). d+h 영업일 종가가 확보된 d 만 계산';
COMMENT ON COLUMN tb_advisor_weight_set.n_eff             IS '겹침 보정 유효 표본 수 = 창/호라이즌';
COMMENT ON COLUMN tb_advisor_weight_set.is_active         IS '스크리닝이 현재 쓰는 세트 (호라이즌마다 하나만 TRUE)';
COMMENT ON COLUMN tb_advisor_weight_set.horizon_days      IS '학습 호라이즌(거래일): 5 DAILY | 20 H20 (advisor.horizons learn=true, M5 2026-09-25). 기존 행은 전부 5';

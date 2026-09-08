-- =============================================
-- 주식(한국투자증권 KIS Open API) 수집 모듈 테이블 DDL
-- PostgreSQL
-- =============================================
-- 이 프로젝트에는 Flyway 가 없다. 배포 전에 psql 로 수기 적용하고 schema-postgres.sql 과 동기화한다.
-- 시각 컬럼은 TIMESTAMPTZ(6), DB 는 UTC 로 운영한다. 거래일(trade_date)은 KST 영업일 기준 DATE 다.
-- 가격은 NUMERIC(정확성), 파생 지표는 DOUBLE PRECISION(연산 속도)으로 나눈다.
-- 시계열 대량 테이블은 JPA 엔티티 없이 JdbcTemplate 배치 upsert 로 쓴다.
-- 파생 MV/뷰(수정주가 계수, 지표)는 db/stock-derived.sql 에서 별도로 생성한다.
-- =============================================


-- ---------------------------------------------
-- 종목 마스터 (현재 상태)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_master
(
    ticker              VARCHAR(10)    NOT NULL PRIMARY KEY,
    stock_name          VARCHAR(100)   NOT NULL,
    market_type         VARCHAR(10)    NOT NULL,
    security_group      VARCHAR(4)     NOT NULL,
    standard_code       VARCHAR(12)             DEFAULT NULL,
    listing_date        DATE                    DEFAULT NULL,
    listed_shares       BIGINT                  DEFAULT NULL,
    capital             BIGINT                  DEFAULT NULL,
    par_value           NUMERIC(18,2)           DEFAULT NULL,
    settle_month        VARCHAR(2)              DEFAULT NULL,
    sector_large_code   VARCHAR(10)             DEFAULT NULL,
    sector_mid_code     VARCHAR(10)             DEFAULT NULL,
    sector_small_code   VARCHAR(10)             DEFAULT NULL,
    kospi200_sector     VARCHAR(10)             DEFAULT NULL,
    is_kospi200         BOOLEAN        NOT NULL DEFAULT FALSE,
    is_krx300           BOOLEAN        NOT NULL DEFAULT FALSE,
    is_suspended        BOOLEAN        NOT NULL DEFAULT FALSE,
    is_administrative   BOOLEAN        NOT NULL DEFAULT FALSE,
    is_liquidating      BOOLEAN        NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE,
    delisting_date      DATE                    DEFAULT NULL,
    created_at          TIMESTAMPTZ(6) NOT NULL,
    created_by          VARCHAR(255)            DEFAULT NULL,
    updated_at          TIMESTAMPTZ(6) NOT NULL,
    updated_by          VARCHAR(255)            DEFAULT NULL
);

COMMENT ON TABLE  tb_stock_master                   IS '종목 마스터 현재 상태 (KIS 마스터 파일 기준). 상폐 종목도 행을 지우지 않고 is_active=false 로 보존한다';
COMMENT ON COLUMN tb_stock_master.ticker            IS '단축 종목코드 (6자리, 예: 005930)';
COMMENT ON COLUMN tb_stock_master.stock_name        IS '종목명 (한글)';
COMMENT ON COLUMN tb_stock_master.market_type       IS '시장 구분: KOSPI | KOSDAQ';
COMMENT ON COLUMN tb_stock_master.security_group    IS '증권 그룹코드: ST 주권, EF ETF, EN ETN, MF 투자회사, RT 리츠 등';
COMMENT ON COLUMN tb_stock_master.standard_code     IS '표준코드 (ISIN, 12자리)';
COMMENT ON COLUMN tb_stock_master.listing_date      IS '상장일';
COMMENT ON COLUMN tb_stock_master.listed_shares     IS '상장주식수 (주)';
COMMENT ON COLUMN tb_stock_master.capital           IS '자본금 (백만원 단위, 마스터 파일 표기 그대로)';
COMMENT ON COLUMN tb_stock_master.par_value         IS '액면가 (원)';
COMMENT ON COLUMN tb_stock_master.settle_month      IS '결산월 (MM)';
COMMENT ON COLUMN tb_stock_master.sector_large_code IS '지수업종 대분류 코드';
COMMENT ON COLUMN tb_stock_master.sector_mid_code   IS '지수업종 중분류 코드 (1차 섹터 정본)';
COMMENT ON COLUMN tb_stock_master.sector_small_code IS '지수업종 소분류 코드';
COMMENT ON COLUMN tb_stock_master.kospi200_sector   IS 'KOSPI200 섹터업종 코드';
COMMENT ON COLUMN tb_stock_master.is_kospi200       IS 'KOSPI200 구성종목 여부';
COMMENT ON COLUMN tb_stock_master.is_krx300         IS 'KRX300 구성종목 여부';
COMMENT ON COLUMN tb_stock_master.is_suspended      IS '거래정지 여부';
COMMENT ON COLUMN tb_stock_master.is_administrative IS '관리종목 여부';
COMMENT ON COLUMN tb_stock_master.is_liquidating    IS '정리매매 여부';
COMMENT ON COLUMN tb_stock_master.is_active         IS '활성(상장 중) 여부. 마스터 파일에서 사라지면 false';
COMMENT ON COLUMN tb_stock_master.delisting_date    IS '상장폐지 감지일 (활성이면 NULL)';
COMMENT ON COLUMN tb_stock_master.created_at        IS '생성일시';
COMMENT ON COLUMN tb_stock_master.created_by        IS '생성자';
COMMENT ON COLUMN tb_stock_master.updated_at        IS '수정일시';
COMMENT ON COLUMN tb_stock_master.updated_by        IS '수정자';

CREATE INDEX IF NOT EXISTS idx_stock_master_active
    ON tb_stock_master (market_type) WHERE is_active = TRUE;


-- ---------------------------------------------
-- 종목 마스터 이력 (SCD2). 이력 시작점은 구축일이며 과거 구성은 재현할 수 없다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_master_history
(
    ticker            VARCHAR(10)    NOT NULL,
    valid_from        DATE           NOT NULL,
    valid_to          DATE                    DEFAULT NULL,
    stock_name        VARCHAR(100)   NOT NULL,
    market_type       VARCHAR(10)    NOT NULL,
    security_group    VARCHAR(4)     NOT NULL,
    sector_mid_code   VARCHAR(10)             DEFAULT NULL,
    is_kospi200       BOOLEAN        NOT NULL,
    is_krx300         BOOLEAN        NOT NULL,
    is_suspended      BOOLEAN        NOT NULL,
    is_administrative BOOLEAN        NOT NULL,
    is_active         BOOLEAN        NOT NULL,
    listed_shares     BIGINT                  DEFAULT NULL,
    snapshot_hash     VARCHAR(64)    NOT NULL,
    created_at        TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_master_history PRIMARY KEY (ticker, valid_from)
);

COMMENT ON TABLE  tb_stock_master_history                   IS '종목 마스터 SCD2 이력. snapshot_hash 가 달라진 날에만 새 행을 연다';
COMMENT ON COLUMN tb_stock_master_history.ticker            IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_master_history.valid_from        IS '유효 시작일';
COMMENT ON COLUMN tb_stock_master_history.valid_to          IS '유효 종료일 (현재 유효 행이면 NULL)';
COMMENT ON COLUMN tb_stock_master_history.stock_name        IS '종목명';
COMMENT ON COLUMN tb_stock_master_history.market_type       IS '시장 구분';
COMMENT ON COLUMN tb_stock_master_history.security_group    IS '증권 그룹코드';
COMMENT ON COLUMN tb_stock_master_history.sector_mid_code   IS '지수업종 중분류 코드';
COMMENT ON COLUMN tb_stock_master_history.is_kospi200       IS 'KOSPI200 구성종목 여부';
COMMENT ON COLUMN tb_stock_master_history.is_krx300         IS 'KRX300 구성종목 여부';
COMMENT ON COLUMN tb_stock_master_history.is_suspended      IS '거래정지 여부';
COMMENT ON COLUMN tb_stock_master_history.is_administrative IS '관리종목 여부';
COMMENT ON COLUMN tb_stock_master_history.is_active         IS '활성 여부';
COMMENT ON COLUMN tb_stock_master_history.listed_shares     IS '상장주식수';
COMMENT ON COLUMN tb_stock_master_history.snapshot_hash     IS 'SCD 대상 컬럼의 해시 (변경 감지용)';
COMMENT ON COLUMN tb_stock_master_history.created_at        IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_master_history_current
    ON tb_stock_master_history (ticker) WHERE valid_to IS NULL;


-- ---------------------------------------------
-- 휴장일 (KIS 국내휴장일조회 CTCA0903R). 과거 영업일 정본은 KOSPI 지수 일봉의 날짜 집합이다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_market_holiday
(
    trade_date    DATE           NOT NULL PRIMARY KEY,
    is_open       BOOLEAN        NOT NULL,
    is_business   BOOLEAN        NOT NULL,
    is_trading    BOOLEAN        NOT NULL,
    is_settlement BOOLEAN        NOT NULL,
    collected_at  TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  tb_stock_market_holiday               IS '국내 주식시장 개장/영업/거래/결제일 캘린더 (미래 판정용)';
COMMENT ON COLUMN tb_stock_market_holiday.trade_date    IS '기준일';
COMMENT ON COLUMN tb_stock_market_holiday.is_open       IS '개장일 여부 (opnd_yn)';
COMMENT ON COLUMN tb_stock_market_holiday.is_business   IS '영업일 여부 (bzdy_yn)';
COMMENT ON COLUMN tb_stock_market_holiday.is_trading    IS '거래일 여부 (tr_day_yn)';
COMMENT ON COLUMN tb_stock_market_holiday.is_settlement IS '결제일 여부 (sttl_day_yn)';
COMMENT ON COLUMN tb_stock_market_holiday.collected_at  IS '적재 시각';


-- ---------------------------------------------
-- 업종·지수 코드 마스터 (idxcode.mst). 지수 백필의 대상 목록이며 섹터 이름의 출처다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_index_master
(
    index_code   VARCHAR(20)    NOT NULL PRIMARY KEY,
    market_div   VARCHAR(2)              DEFAULT NULL,
    index_name   VARCHAR(100)   NOT NULL,
    is_active    BOOLEAN        NOT NULL DEFAULT TRUE,
    collected_at TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE tb_stock_index_master IS '업종·지수 코드 마스터 (KIS idxcode.mst)';
COMMENT ON COLUMN tb_stock_index_master.index_code IS '업종코드 4자리 (FHKUP03500100 FID_INPUT_ISCD)';
COMMENT ON COLUMN tb_stock_index_master.market_div IS '시장구분 1자리 (파일 맨 앞 문자)';
COMMENT ON COLUMN tb_stock_index_master.index_name IS '업종명';
COMMENT ON COLUMN tb_stock_index_master.is_active IS '최근 파일에 존재하는지 (사라진 코드는 비활성)';
COMMENT ON COLUMN tb_stock_index_master.collected_at IS '마지막 갱신 시각';

-- ---------------------------------------------
-- 시장·업종 지수 일봉 (FHKUP03500100)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_index_daily
(
    index_code    VARCHAR(20)    NOT NULL,
    trade_date    DATE           NOT NULL,
    open_price    NUMERIC(18,4)  NOT NULL,
    high_price    NUMERIC(18,4)  NOT NULL,
    low_price     NUMERIC(18,4)  NOT NULL,
    close_price   NUMERIC(18,4)  NOT NULL,
    volume        BIGINT                  DEFAULT NULL,
    trading_value BIGINT                  DEFAULT NULL,
    change_rate   NUMERIC(8,4)            DEFAULT NULL,
    collected_at  TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_index_daily PRIMARY KEY (index_code, trade_date)
);

COMMENT ON TABLE  tb_stock_index_daily               IS '시장·업종 지수 일봉. 0001 KOSPI, 1001 KOSDAQ, 2001 KOSPI200, 그 외 KRX 업종코드';
COMMENT ON COLUMN tb_stock_index_daily.index_code    IS '지수(업종) 코드';
COMMENT ON COLUMN tb_stock_index_daily.trade_date    IS '거래일';
COMMENT ON COLUMN tb_stock_index_daily.open_price    IS '시가';
COMMENT ON COLUMN tb_stock_index_daily.high_price    IS '고가';
COMMENT ON COLUMN tb_stock_index_daily.low_price     IS '저가';
COMMENT ON COLUMN tb_stock_index_daily.close_price   IS '종가';
COMMENT ON COLUMN tb_stock_index_daily.volume        IS '거래량 (천주)';
COMMENT ON COLUMN tb_stock_index_daily.trading_value IS '거래대금 (백만원)';
COMMENT ON COLUMN tb_stock_index_daily.change_rate   IS '전일대비 등락률 (%)';
COMMENT ON COLUMN tb_stock_index_daily.collected_at  IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_index_daily_date
    ON tb_stock_index_daily (trade_date);


-- ---------------------------------------------
-- 종목 일봉 (원주가 정본, FHKST03010100 FID_ORG_ADJ_PRC=1)
-- 수정주가는 저장하지 않는다. 조회 시점 기준으로 재계산되어 재현 불가능하기 때문이다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_daily_price
(
    ticker         VARCHAR(10)    NOT NULL,
    trade_date     DATE           NOT NULL,
    open_price     NUMERIC(18,2)  NOT NULL,
    high_price     NUMERIC(18,2)  NOT NULL,
    low_price      NUMERIC(18,2)  NOT NULL,
    close_price    NUMERIC(18,2)  NOT NULL,
    volume         BIGINT         NOT NULL,
    trading_value  BIGINT         NOT NULL,
    prev_diff      NUMERIC(18,2)           DEFAULT NULL,
    prev_diff_sign VARCHAR(1)              DEFAULT NULL,
    change_rate    NUMERIC(8,4)            DEFAULT NULL,
    flng_cls_code  VARCHAR(2)              DEFAULT NULL,
    prtt_rate      NUMERIC(12,6)           DEFAULT NULL,
    mod_yn         VARCHAR(1)              DEFAULT NULL,
    revl_issu_reas VARCHAR(10)             DEFAULT NULL,
    collected_at   TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_daily_price PRIMARY KEY (ticker, trade_date)
);

COMMENT ON TABLE  tb_stock_daily_price                IS '종목 일봉 (원주가 정본). 수정주가는 mv_stock_adjust_factor 계수를 곱해 뷰에서 만든다';
COMMENT ON COLUMN tb_stock_daily_price.ticker         IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_daily_price.trade_date     IS '거래일';
COMMENT ON COLUMN tb_stock_daily_price.open_price     IS '시가 (원, 원주가)';
COMMENT ON COLUMN tb_stock_daily_price.high_price     IS '고가 (원, 원주가)';
COMMENT ON COLUMN tb_stock_daily_price.low_price      IS '저가 (원, 원주가)';
COMMENT ON COLUMN tb_stock_daily_price.close_price    IS '종가 (원, 원주가)';
COMMENT ON COLUMN tb_stock_daily_price.volume         IS '누적 거래량 (주)';
COMMENT ON COLUMN tb_stock_daily_price.trading_value  IS '누적 거래대금 (원)';
COMMENT ON COLUMN tb_stock_daily_price.prev_diff      IS '전일 대비 (prdy_vrss)';
COMMENT ON COLUMN tb_stock_daily_price.prev_diff_sign IS '전일 대비 부호 (prdy_vrss_sign)';
COMMENT ON COLUMN tb_stock_daily_price.change_rate    IS '전일 대비 등락률 (%)';
COMMENT ON COLUMN tb_stock_daily_price.flng_cls_code  IS '락 구분 코드 (권리락, 배당락 등). 수정계수 역산의 1차 힌트';
COMMENT ON COLUMN tb_stock_daily_price.prtt_rate      IS '분할 비율 (prtt_rate)';
COMMENT ON COLUMN tb_stock_daily_price.mod_yn         IS '수정주가 반영 여부 (mod_yn)';
COMMENT ON COLUMN tb_stock_daily_price.revl_issu_reas IS '재평가 사유 코드 (revl_issu_reas)';
COMMENT ON COLUMN tb_stock_daily_price.collected_at   IS '적재(최종 갱신) 시각';

-- 특정일 전 종목 횡단면 조회(랭킹)는 PK 로 서빙되지 않는다. 커버링 인덱스로 힙 접근을 줄인다
CREATE INDEX IF NOT EXISTS idx_stock_daily_price_date
    ON tb_stock_daily_price (trade_date)
    INCLUDE (ticker, close_price, trading_value, change_rate);


-- ---------------------------------------------
-- 종목 밸류에이션 일별 스냅샷 (FHKST01010100 현재가). 당일만 제공되어 소급 불가하므로 매일 쌓는다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_valuation_daily
(
    ticker            VARCHAR(10)    NOT NULL,
    trade_date        DATE           NOT NULL,
    market_cap        BIGINT                  DEFAULT NULL,
    listed_shares     BIGINT                  DEFAULT NULL,
    per               NUMERIC(12,4)           DEFAULT NULL,
    pbr               NUMERIC(12,4)           DEFAULT NULL,
    eps               NUMERIC(18,2)           DEFAULT NULL,
    bps               NUMERIC(18,2)           DEFAULT NULL,
    week52_high       NUMERIC(18,2)           DEFAULT NULL,
    week52_low        NUMERIC(18,2)           DEFAULT NULL,
    foreign_hold_rate NUMERIC(8,4)            DEFAULT NULL,
    collected_at      TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_valuation_daily PRIMARY KEY (ticker, trade_date)
);

COMMENT ON TABLE  tb_stock_valuation_daily                   IS '종목 밸류에이션 일별 스냅샷 (시총, PER/PBR, 52주 고저, 외인지분율)';
COMMENT ON COLUMN tb_stock_valuation_daily.ticker            IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_valuation_daily.trade_date        IS '기준 거래일';
COMMENT ON COLUMN tb_stock_valuation_daily.market_cap        IS '시가총액 (억원, hts_avls)';
COMMENT ON COLUMN tb_stock_valuation_daily.listed_shares     IS '상장주식수 (주, lstn_stcn)';
COMMENT ON COLUMN tb_stock_valuation_daily.per               IS 'PER';
COMMENT ON COLUMN tb_stock_valuation_daily.pbr               IS 'PBR';
COMMENT ON COLUMN tb_stock_valuation_daily.eps               IS 'EPS (원)';
COMMENT ON COLUMN tb_stock_valuation_daily.bps               IS 'BPS (원)';
COMMENT ON COLUMN tb_stock_valuation_daily.week52_high       IS '52주 최고가 (원). 일봉 계산값과 교차검증용';
COMMENT ON COLUMN tb_stock_valuation_daily.week52_low        IS '52주 최저가 (원)';
COMMENT ON COLUMN tb_stock_valuation_daily.foreign_hold_rate IS '외국인 보유 비율 (%)';
COMMENT ON COLUMN tb_stock_valuation_daily.collected_at      IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_valuation_daily_date
    ON tb_stock_valuation_daily (trade_date);


-- ---------------------------------------------
-- 투자자별 일별 순매수 (FHPTJ04160001 / FHKST01010900)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_investor_daily
(
    ticker              VARCHAR(10)    NOT NULL,
    trade_date          DATE           NOT NULL,
    foreign_net_amt     BIGINT         NOT NULL DEFAULT 0,
    institution_net_amt BIGINT         NOT NULL DEFAULT 0,
    individual_net_amt  BIGINT         NOT NULL DEFAULT 0,
    pension_net_amt     BIGINT         NOT NULL DEFAULT 0,
    other_net_amt       BIGINT         NOT NULL DEFAULT 0,
    foreign_net_qty     BIGINT                  DEFAULT NULL,
    institution_net_qty BIGINT                  DEFAULT NULL,
    individual_net_qty  BIGINT                  DEFAULT NULL,
    collected_at        TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_investor_daily PRIMARY KEY (ticker, trade_date)
);

COMMENT ON TABLE  tb_stock_investor_daily                     IS '투자자별 일별 순매수. 양수=순매수, 음수=순매도';
COMMENT ON COLUMN tb_stock_investor_daily.ticker              IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_investor_daily.trade_date          IS '거래일';
COMMENT ON COLUMN tb_stock_investor_daily.foreign_net_amt     IS '외국인 순매수 금액 (원)';
COMMENT ON COLUMN tb_stock_investor_daily.institution_net_amt IS '기관 순매수 금액 (원)';
COMMENT ON COLUMN tb_stock_investor_daily.individual_net_amt  IS '개인 순매수 금액 (원)';
COMMENT ON COLUMN tb_stock_investor_daily.pension_net_amt     IS '연기금 등 순매수 금액 (원)';
COMMENT ON COLUMN tb_stock_investor_daily.other_net_amt       IS '기타 순매수 금액 (원)';
COMMENT ON COLUMN tb_stock_investor_daily.foreign_net_qty     IS '외국인 순매수 수량 (주)';
COMMENT ON COLUMN tb_stock_investor_daily.institution_net_qty IS '기관 순매수 수량 (주)';
COMMENT ON COLUMN tb_stock_investor_daily.individual_net_qty  IS '개인 순매수 수량 (주)';
COMMENT ON COLUMN tb_stock_investor_daily.collected_at        IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_investor_daily_date
    ON tb_stock_investor_daily (trade_date);


-- ---------------------------------------------
-- 시장 통계 일별 (P1): 공매도 FHPST04830000, 신용잔고 FHPST04760000, 프로그램매매 FHPPG04650201.
-- 출처별로 컬럼을 따로 채우므로 upsert 는 소스별 컬럼만 갱신한다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_market_stat_daily
(
    ticker            VARCHAR(10)    NOT NULL,
    trade_date        DATE           NOT NULL,
    short_sale_qty    BIGINT                  DEFAULT NULL,
    short_sale_amt    BIGINT                  DEFAULT NULL,
    short_sale_ratio  NUMERIC(8,4)            DEFAULT NULL,
    credit_loan_qty   BIGINT                  DEFAULT NULL,
    credit_loan_amt   BIGINT                  DEFAULT NULL,
    credit_loan_ratio NUMERIC(8,4)            DEFAULT NULL,
    stock_loan_qty    BIGINT                  DEFAULT NULL,
    program_net_qty   BIGINT                  DEFAULT NULL,
    program_net_amt   BIGINT                  DEFAULT NULL,
    collected_at      TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_market_stat_daily PRIMARY KEY (ticker, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_stock_market_stat_daily_date
    ON tb_stock_market_stat_daily (trade_date);
COMMENT ON TABLE tb_stock_market_stat_daily IS '종목 시장 통계 일별 (공매도·신용잔고·프로그램매매)';
COMMENT ON COLUMN tb_stock_market_stat_daily.ticker IS '종목코드';
COMMENT ON COLUMN tb_stock_market_stat_daily.trade_date IS '거래일';
COMMENT ON COLUMN tb_stock_market_stat_daily.short_sale_qty IS '공매도 체결 수량';
COMMENT ON COLUMN tb_stock_market_stat_daily.short_sale_amt IS '공매도 거래 대금';
COMMENT ON COLUMN tb_stock_market_stat_daily.short_sale_ratio IS '공매도 거래량 비중(%)';
COMMENT ON COLUMN tb_stock_market_stat_daily.credit_loan_qty IS '융자 잔고 주수';
COMMENT ON COLUMN tb_stock_market_stat_daily.credit_loan_amt IS '융자 잔고 금액';
COMMENT ON COLUMN tb_stock_market_stat_daily.credit_loan_ratio IS '융자 잔고 비율(%)';
COMMENT ON COLUMN tb_stock_market_stat_daily.stock_loan_qty IS '대주 잔고 주수';
COMMENT ON COLUMN tb_stock_market_stat_daily.program_net_qty IS '프로그램매매 순매수 수량';
COMMENT ON COLUMN tb_stock_market_stat_daily.program_net_amt IS '프로그램매매 순매수 대금';
COMMENT ON COLUMN tb_stock_market_stat_daily.collected_at IS '마지막 수집 시각';

-- ---------------------------------------------
-- 기업행사 원본 (예탁원 ksdinfo API + 일봉 output2 힌트)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_corporate_action
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker         VARCHAR(10)    NOT NULL,
    effective_date DATE           NOT NULL,
    action_type    VARCHAR(20)    NOT NULL,
    ratio_before   NUMERIC(18,6)           DEFAULT NULL,
    ratio_after    NUMERIC(18,6)           DEFAULT NULL,
    cash_amount    NUMERIC(18,2)           DEFAULT NULL,
    source         VARCHAR(20)    NOT NULL,
    raw_json       JSONB                   DEFAULT NULL,
    created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_stock_corporate_action UNIQUE (ticker, effective_date, action_type, source)
);

COMMENT ON TABLE  tb_stock_corporate_action                IS '기업행사 원본. 수정주가 계수(tb_stock_adjust_event)의 근거 데이터';
COMMENT ON COLUMN tb_stock_corporate_action.id             IS '식별자';
COMMENT ON COLUMN tb_stock_corporate_action.ticker         IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_corporate_action.effective_date IS '효력(권리락) 기준일';
COMMENT ON COLUMN tb_stock_corporate_action.action_type    IS '행사 유형: SPLIT | REVERSE_SPLIT | BONUS_ISSUE | RIGHTS_ISSUE | CAPITAL_REDUCTION | MERGER_SPLIT | LISTING | DIVIDEND | CHART_HINT';
COMMENT ON COLUMN tb_stock_corporate_action.ratio_before   IS '행사 전 비율(구주)';
COMMENT ON COLUMN tb_stock_corporate_action.ratio_after    IS '행사 후 비율(신주)';
COMMENT ON COLUMN tb_stock_corporate_action.cash_amount    IS '현금 금액 (배당금 등, 원)';
COMMENT ON COLUMN tb_stock_corporate_action.source         IS '출처: KSD 예탁원 | CHART_HINT 일봉 힌트 | MANUAL 수기';
COMMENT ON COLUMN tb_stock_corporate_action.raw_json       IS 'API 원본 응답 (소량·스키마 불안정 도메인만 보관)';
COMMENT ON COLUMN tb_stock_corporate_action.created_at     IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_corporate_action_ticker
    ON tb_stock_corporate_action (ticker, effective_date);


-- ---------------------------------------------
-- 수정주가 계수 이벤트 (기업행사에서 파생된 검증 가능한 단일 진실)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_adjust_event
(
    ticker         VARCHAR(10)    NOT NULL,
    effective_date DATE           NOT NULL,
    action_type    VARCHAR(20)    NOT NULL,
    price_factor   NUMERIC(18,10) NOT NULL,
    volume_factor  NUMERIC(18,10) NOT NULL,
    verified       BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_adjust_event PRIMARY KEY (ticker, effective_date, action_type)
);

COMMENT ON TABLE  tb_stock_adjust_event                IS '수정주가 계수 이벤트. effective_date 이전 가격에 price_factor 를 곱한다';
COMMENT ON COLUMN tb_stock_adjust_event.ticker         IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_adjust_event.effective_date IS '효력일 (이 날부터 새 기준)';
COMMENT ON COLUMN tb_stock_adjust_event.action_type    IS '행사 유형';
COMMENT ON COLUMN tb_stock_adjust_event.price_factor   IS '과거 가격에 곱할 비율 (1:5 분할이면 0.2)';
COMMENT ON COLUMN tb_stock_adjust_event.volume_factor  IS '과거 거래량에 곱할 비율 (1:5 분할이면 5.0)';
COMMENT ON COLUMN tb_stock_adjust_event.verified       IS 'KIS 수정주가 모드와 대조 검증 완료 여부';
COMMENT ON COLUMN tb_stock_adjust_event.created_at     IS '적재 시각';


-- ---------------------------------------------
-- 재무제표 (finance/* 7종). 발표일이 없어 available_from 으로 룩어헤드를 막는다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_financial
(
    ticker           VARCHAR(10)    NOT NULL,
    fiscal_period    VARCHAR(6)     NOT NULL,
    period_type      VARCHAR(1)     NOT NULL,
    revision_seq     INTEGER        NOT NULL DEFAULT 0,
    disclosed_at     DATE                    DEFAULT NULL,
    available_from   DATE           NOT NULL,
    available_rule   VARCHAR(20)    NOT NULL,
    first_seen_at    TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    revenue          BIGINT                  DEFAULT NULL,
    operating_profit BIGINT                  DEFAULT NULL,
    net_income       BIGINT                  DEFAULT NULL,
    total_asset      BIGINT                  DEFAULT NULL,
    total_equity     BIGINT                  DEFAULT NULL,
    total_debt       BIGINT                  DEFAULT NULL,
    roe              NUMERIC(12,4)           DEFAULT NULL,
    debt_ratio       NUMERIC(12,4)           DEFAULT NULL,
    revenue_growth   NUMERIC(12,4)           DEFAULT NULL,
    profit_growth    NUMERIC(12,4)           DEFAULT NULL,
    operating_profit_growth NUMERIC(12,4)    DEFAULT NULL,
    equity_growth    NUMERIC(12,4)           DEFAULT NULL,
    asset_growth     NUMERIC(12,4)           DEFAULT NULL,
    roa              NUMERIC(12,4)           DEFAULT NULL,
    net_margin       NUMERIC(12,4)           DEFAULT NULL,
    gross_margin     NUMERIC(12,4)           DEFAULT NULL,
    current_ratio    NUMERIC(12,4)           DEFAULT NULL,
    quick_ratio      NUMERIC(12,4)           DEFAULT NULL,
    borrowing_dependency NUMERIC(12,4)       DEFAULT NULL,
    raw_json         JSONB                   DEFAULT NULL,
    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_financial PRIMARY KEY (ticker, fiscal_period, period_type, revision_seq)
);

COMMENT ON TABLE  tb_stock_financial                  IS '재무제표 point-in-time (손익·대차·재무비율·성장성·수익성·안정성 6종 병합). 백테스트는 max(available_from, first_seen_at) 이후에만 이 값을 볼 수 있다';
COMMENT ON COLUMN tb_stock_financial.ticker           IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_financial.fiscal_period    IS '결산기 (YYYYMM)';
COMMENT ON COLUMN tb_stock_financial.period_type      IS 'Y 연간 | Q 분기';
COMMENT ON COLUMN tb_stock_financial.revision_seq     IS '동일 결산기 정정 회차 (0 부터)';
COMMENT ON COLUMN tb_stock_financial.disclosed_at     IS '실제 공시일 (확보 가능한 경우만)';
COMMENT ON COLUMN tb_stock_financial.available_from   IS '이 값을 알 수 있게 된 보수적 날짜';
COMMENT ON COLUMN tb_stock_financial.available_rule   IS 'available_from 산출 규칙: DISCLOSED | LAG_45D | LAG_90D | COLLECTED';
COMMENT ON COLUMN tb_stock_financial.first_seen_at    IS '우리가 이 값을 처음 관측한 시각';
COMMENT ON COLUMN tb_stock_financial.revenue          IS '매출액 (백만원)';
COMMENT ON COLUMN tb_stock_financial.operating_profit IS '영업이익 (백만원)';
COMMENT ON COLUMN tb_stock_financial.net_income       IS '당기순이익 (백만원)';
COMMENT ON COLUMN tb_stock_financial.total_asset      IS '자산총계 (백만원)';
COMMENT ON COLUMN tb_stock_financial.total_equity     IS '자본총계 (백만원)';
COMMENT ON COLUMN tb_stock_financial.total_debt       IS '부채총계 (백만원)';
COMMENT ON COLUMN tb_stock_financial.roe              IS 'ROE (%)';
COMMENT ON COLUMN tb_stock_financial.debt_ratio       IS '부채비율 (%)';
COMMENT ON COLUMN tb_stock_financial.revenue_growth   IS '매출액 증가율 (%)';
COMMENT ON COLUMN tb_stock_financial.profit_growth    IS '순이익 증가율 (%) (재무비율 ntin_inrt. 영업이익 증가율은 operating_profit_growth)';
COMMENT ON COLUMN tb_stock_financial.operating_profit_growth IS '영업이익 증가율 (%) (성장성 bsop_prfi_inrt)';
COMMENT ON COLUMN tb_stock_financial.equity_growth    IS '자기자본 증가율 (%) (성장성 equt_inrt)';
COMMENT ON COLUMN tb_stock_financial.asset_growth     IS '총자산 증가율 (%) (성장성 totl_aset_inrt)';
COMMENT ON COLUMN tb_stock_financial.roa              IS '총자본 순이익율 ROA (%) (수익성 cptl_ntin_rate)';
COMMENT ON COLUMN tb_stock_financial.net_margin       IS '매출액 순이익율 (%) (수익성 sale_ntin_rate)';
COMMENT ON COLUMN tb_stock_financial.gross_margin     IS '매출액 총이익율 (%) (수익성 sale_totl_rate)';
COMMENT ON COLUMN tb_stock_financial.current_ratio    IS '유동비율 (%) (안정성 crnt_rate)';
COMMENT ON COLUMN tb_stock_financial.quick_ratio      IS '당좌비율 (%) (안정성 quck_rate)';
COMMENT ON COLUMN tb_stock_financial.borrowing_dependency IS '차입금 의존도 (%) (안정성 bram_depn)';
COMMENT ON COLUMN tb_stock_financial.raw_json         IS 'API 원본 응답 (계정과목이 종목별로 달라 원본 보관)';
COMMENT ON COLUMN tb_stock_financial.created_at       IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_financial_available
    ON tb_stock_financial (ticker, available_from DESC);


-- ---------------------------------------------
-- 종목-섹터 매핑 (N:M, SCD). 1차 정본은 KIS 지수업종 중분류, THEME/CUSTOM 병기 가능
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_sector_map
(
    ticker      VARCHAR(10)    NOT NULL,
    sector_code VARCHAR(20)    NOT NULL,
    valid_from  DATE           NOT NULL,
    valid_to    DATE                    DEFAULT NULL,
    sector_name VARCHAR(100)   NOT NULL,
    source      VARCHAR(20)    NOT NULL,
    created_at  TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_sector_map PRIMARY KEY (ticker, sector_code, valid_from)
);

COMMENT ON TABLE  tb_stock_sector_map             IS '종목-섹터 매핑 이력. 한 종목이 복수 섹터에 속할 수 있다';
COMMENT ON COLUMN tb_stock_sector_map.ticker      IS '단축 종목코드';
COMMENT ON COLUMN tb_stock_sector_map.sector_code IS '섹터 코드 (KRX 업종코드, 테마코드, 커스텀 코드)';
COMMENT ON COLUMN tb_stock_sector_map.valid_from  IS '매핑 유효 시작일';
COMMENT ON COLUMN tb_stock_sector_map.valid_to    IS '매핑 유효 종료일 (현재 매핑이면 NULL)';
COMMENT ON COLUMN tb_stock_sector_map.sector_name IS '섹터명';
COMMENT ON COLUMN tb_stock_sector_map.source      IS '매핑 출처: KRX | THEME | CUSTOM';
COMMENT ON COLUMN tb_stock_sector_map.created_at  IS '적재 시각';

CREATE INDEX IF NOT EXISTS idx_stock_sector_map_active
    ON tb_stock_sector_map (sector_code) WHERE valid_to IS NULL;


-- ---------------------------------------------
-- 해외 지수·환율·ETF 일봉 (FHKST03030100 / HHDFS76240000)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_global_market_daily
(
    symbol       VARCHAR(20)    NOT NULL,
    trade_date   DATE           NOT NULL,
    market_div   VARCHAR(2)     NOT NULL,
    exchange     VARCHAR(10)             DEFAULT NULL,
    open_price   NUMERIC(18,4)           DEFAULT NULL,
    high_price   NUMERIC(18,4)           DEFAULT NULL,
    low_price    NUMERIC(18,4)           DEFAULT NULL,
    close_price  NUMERIC(18,4)  NOT NULL,
    volume       BIGINT                  DEFAULT NULL,
    change_rate  NUMERIC(8,4)            DEFAULT NULL,
    collected_at TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_global_market_daily PRIMARY KEY (symbol, trade_date)
);

COMMENT ON TABLE  tb_stock_global_market_daily              IS '해외 지수·환율·ETF·개별주 일봉 (미국 시장 참조 지표)';
COMMENT ON COLUMN tb_stock_global_market_daily.symbol       IS '심볼 (.DJI, COMP, SPX, USD/KRW, SOXX, NVDA 등)';
COMMENT ON COLUMN tb_stock_global_market_daily.trade_date   IS '현지 거래일';
COMMENT ON COLUMN tb_stock_global_market_daily.market_div   IS 'N 해외지수 | X 환율 | EQ 해외주식·ETF';
COMMENT ON COLUMN tb_stock_global_market_daily.exchange     IS '거래소 코드 (NAS, NYS, AMS 등, 해외주식만)';
COMMENT ON COLUMN tb_stock_global_market_daily.open_price   IS '시가';
COMMENT ON COLUMN tb_stock_global_market_daily.high_price   IS '고가';
COMMENT ON COLUMN tb_stock_global_market_daily.low_price    IS '저가';
COMMENT ON COLUMN tb_stock_global_market_daily.close_price  IS '종가';
COMMENT ON COLUMN tb_stock_global_market_daily.volume       IS '거래량';
COMMENT ON COLUMN tb_stock_global_market_daily.change_rate  IS '전일 대비 등락률 (%)';
COMMENT ON COLUMN tb_stock_global_market_daily.collected_at IS '적재 시각';


-- ---------------------------------------------
-- 국내 섹터 ↔ 미국 참조 지표 매핑 (시드 데이터, 소스 추가 시 코드 변경 없이 행만 추가)
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_global_sector_map
(
    sector_code   VARCHAR(20)    NOT NULL,
    global_symbol VARCHAR(20)    NOT NULL,
    weight        NUMERIC(6,4)   NOT NULL DEFAULT 1.0,
    created_at    TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_global_sector_map PRIMARY KEY (sector_code, global_symbol)
);

COMMENT ON TABLE  tb_stock_global_sector_map               IS '국내 섹터와 미국 참조 지표(지수·ETF·개별주) 매핑';
COMMENT ON COLUMN tb_stock_global_sector_map.sector_code   IS '국내 섹터 코드';
COMMENT ON COLUMN tb_stock_global_sector_map.global_symbol IS '해외 심볼';
COMMENT ON COLUMN tb_stock_global_sector_map.weight        IS '가중치';
COMMENT ON COLUMN tb_stock_global_sector_map.created_at    IS '생성 시각';


-- ---------------------------------------------
-- 수집 실행 이력. RUNNING 부분 유니크 인덱스로 동일 잡의 중복 실행을 DB 레벨에서 차단한다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_collect_run
(
    run_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_type       VARCHAR(40)    NOT NULL,
    trigger_type   VARCHAR(20)    NOT NULL,
    target_date    DATE                    DEFAULT NULL,
    range_start    DATE                    DEFAULT NULL,
    range_end      DATE                    DEFAULT NULL,
    status         VARCHAR(20)    NOT NULL,
    started_at     TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    finished_at    TIMESTAMPTZ(6)          DEFAULT NULL,
    duration_ms    BIGINT                  DEFAULT NULL,
    rows_upserted  BIGINT         NOT NULL DEFAULT 0,
    api_call_count BIGINT         NOT NULL DEFAULT 0,
    api_fail_count BIGINT         NOT NULL DEFAULT 0,
    error_message  TEXT                    DEFAULT NULL,
    metadata_json  JSONB                   DEFAULT NULL,
    created_at     TIMESTAMPTZ(6) NOT NULL,
    created_by     VARCHAR(255)            DEFAULT NULL,
    updated_at     TIMESTAMPTZ(6) NOT NULL,
    updated_by     VARCHAR(255)            DEFAULT NULL
);

COMMENT ON TABLE  tb_stock_collect_run                IS '주식 수집 잡 실행 이력';
COMMENT ON COLUMN tb_stock_collect_run.run_id         IS '실행 식별자';
COMMENT ON COLUMN tb_stock_collect_run.job_type       IS '잡 유형 (CollectJobType enum)';
COMMENT ON COLUMN tb_stock_collect_run.trigger_type   IS '트리거 출처: SCHEDULER | API';
COMMENT ON COLUMN tb_stock_collect_run.target_date    IS '단일 날짜 잡의 대상 영업일';
COMMENT ON COLUMN tb_stock_collect_run.range_start    IS '백필 시작일 (포함)';
COMMENT ON COLUMN tb_stock_collect_run.range_end      IS '백필 종료일 (포함)';
COMMENT ON COLUMN tb_stock_collect_run.status         IS '상태: RUNNING | SUCCESS | PARTIAL | FAILED | CANCELED';
COMMENT ON COLUMN tb_stock_collect_run.started_at     IS '실행 시작 시각';
COMMENT ON COLUMN tb_stock_collect_run.finished_at    IS '실행 종료 시각';
COMMENT ON COLUMN tb_stock_collect_run.duration_ms    IS '소요 시간 (ms)';
COMMENT ON COLUMN tb_stock_collect_run.rows_upserted  IS '적재(upsert) 행 수 합계';
COMMENT ON COLUMN tb_stock_collect_run.api_call_count IS 'KIS API 호출 수 (성공 호출은 tb_api_log 에 남기지 않고 이 카운터로만 집계)';
COMMENT ON COLUMN tb_stock_collect_run.api_fail_count IS 'KIS API 최종 실패 수';
COMMENT ON COLUMN tb_stock_collect_run.error_message  IS '실패 시 오류 메시지';
COMMENT ON COLUMN tb_stock_collect_run.metadata_json  IS '단계별 통계·파라미터 등 부가 정보';
COMMENT ON COLUMN tb_stock_collect_run.created_at     IS '생성일시';
COMMENT ON COLUMN tb_stock_collect_run.created_by     IS '생성자';
COMMENT ON COLUMN tb_stock_collect_run.updated_at     IS '수정일시';
COMMENT ON COLUMN tb_stock_collect_run.updated_by     IS '수정자';

CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_collect_run_running
    ON tb_stock_collect_run (job_type) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_stock_collect_run_job
    ON tb_stock_collect_run (job_type, started_at DESC);


-- ---------------------------------------------
-- 종목/지수 단위 재개 지점. run 을 넘어 살아남는다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_collect_checkpoint
(
    job_type        VARCHAR(40)    NOT NULL,
    target_key      VARCHAR(20)    NOT NULL,
    cursor_date     DATE                    DEFAULT NULL,
    earliest_loaded DATE                    DEFAULT NULL,
    latest_loaded   DATE                    DEFAULT NULL,
    status          VARCHAR(20)    NOT NULL,
    attempt_count   INTEGER        NOT NULL DEFAULT 0,
    last_run_id     BIGINT                  DEFAULT NULL,
    error_message   TEXT                    DEFAULT NULL,
    updated_at      TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT pk_stock_collect_checkpoint PRIMARY KEY (job_type, target_key)
);

COMMENT ON TABLE  tb_stock_collect_checkpoint                 IS '수집 체크포인트. DONE(목표 도달)과 EXHAUSTED(KIS 소급 한계)를 구분한다';
COMMENT ON COLUMN tb_stock_collect_checkpoint.job_type        IS '잡 유형';
COMMENT ON COLUMN tb_stock_collect_checkpoint.target_key      IS '종목코드 또는 지수코드';
COMMENT ON COLUMN tb_stock_collect_checkpoint.cursor_date     IS '다음 윈도우의 종료일 (뒤로 밀며 감소)';
COMMENT ON COLUMN tb_stock_collect_checkpoint.earliest_loaded IS '지금까지 확보한 가장 오래된 거래일';
COMMENT ON COLUMN tb_stock_collect_checkpoint.latest_loaded   IS '지금까지 확보한 가장 최근 거래일';
COMMENT ON COLUMN tb_stock_collect_checkpoint.status          IS '상태: PENDING | IN_PROGRESS | DONE | EXHAUSTED | FAILED | PAUSED(윈도우 상한, 재개 가능)';
COMMENT ON COLUMN tb_stock_collect_checkpoint.attempt_count   IS '시도 횟수 (임계 초과 시 FAILED 확정)';
COMMENT ON COLUMN tb_stock_collect_checkpoint.last_run_id     IS '마지막으로 처리한 run_id';
COMMENT ON COLUMN tb_stock_collect_checkpoint.error_message   IS '마지막 오류 메시지';
COMMENT ON COLUMN tb_stock_collect_checkpoint.updated_at      IS '갱신 시각';

CREATE INDEX IF NOT EXISTS idx_stock_collect_checkpoint_status
    ON tb_stock_collect_checkpoint (job_type, status);


-- ---------------------------------------------
-- KIS 접근토큰. 발급은 1분 1회 제한이라 issued_at 으로 게이트를 건다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_kis_token
(
    token_key    VARCHAR(20)    NOT NULL PRIMARY KEY,
    access_token TEXT           NOT NULL,
    token_type   VARCHAR(20)    NOT NULL,
    issued_at    TIMESTAMPTZ(6) NOT NULL,
    expires_at   TIMESTAMPTZ(6) NOT NULL,
    approval_key TEXT                    DEFAULT NULL,
    updated_at   TIMESTAMPTZ(6) NOT NULL
);

COMMENT ON TABLE  tb_stock_kis_token              IS 'KIS Open API 접근토큰 (24시간 유효, 발급 1분 1회 제한)';
COMMENT ON COLUMN tb_stock_kis_token.token_key    IS '토큰 키: REAL 실전 (모의 도입 시 SANDBOX)';
COMMENT ON COLUMN tb_stock_kis_token.access_token IS '접근토큰';
COMMENT ON COLUMN tb_stock_kis_token.token_type   IS '토큰 타입 (Bearer)';
COMMENT ON COLUMN tb_stock_kis_token.issued_at    IS '발급 시각 (1분 1회 제한 판정용)';
COMMENT ON COLUMN tb_stock_kis_token.expires_at   IS '만료 시각';
COMMENT ON COLUMN tb_stock_kis_token.approval_key IS '웹소켓 접속키 (2차, 현재 미사용)';
COMMENT ON COLUMN tb_stock_kis_token.updated_at   IS '갱신 시각';


-- ---------------------------------------------
-- KIS API 실패 기록. 성공 호출은 기록하지 않아 tb_api_log 폭증을 피한다
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_kis_api_failure
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          BIGINT                  DEFAULT NULL,
    tr_id           VARCHAR(20)    NOT NULL,
    target_key      VARCHAR(20)             DEFAULT NULL,
    request_summary VARCHAR(500)            DEFAULT NULL,
    http_status     INTEGER                 DEFAULT NULL,
    kis_rt_cd       VARCHAR(10)             DEFAULT NULL,
    kis_msg_cd      VARCHAR(20)             DEFAULT NULL,
    response_body   VARCHAR(4000)           DEFAULT NULL,
    attempt         INTEGER        NOT NULL DEFAULT 1,
    occurred_at     TIMESTAMPTZ(6) NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  tb_stock_kis_api_failure                 IS 'KIS API 최종 실패 호출 기록 (재시도 소진 또는 업무 오류)';
COMMENT ON COLUMN tb_stock_kis_api_failure.id              IS '식별자';
COMMENT ON COLUMN tb_stock_kis_api_failure.run_id          IS '실패가 발생한 run_id';
COMMENT ON COLUMN tb_stock_kis_api_failure.tr_id           IS 'KIS 거래 ID';
COMMENT ON COLUMN tb_stock_kis_api_failure.target_key      IS '대상 종목/지수 코드';
COMMENT ON COLUMN tb_stock_kis_api_failure.request_summary IS '요청 파라미터 요약';
COMMENT ON COLUMN tb_stock_kis_api_failure.http_status     IS 'HTTP 상태코드';
COMMENT ON COLUMN tb_stock_kis_api_failure.kis_rt_cd       IS 'KIS 응답 rt_cd';
COMMENT ON COLUMN tb_stock_kis_api_failure.kis_msg_cd      IS 'KIS 응답 msg_cd (EGW00201 등)';
COMMENT ON COLUMN tb_stock_kis_api_failure.response_body   IS '응답 본문 (4KB 상한)';
COMMENT ON COLUMN tb_stock_kis_api_failure.attempt         IS '최종 시도 회차';
COMMENT ON COLUMN tb_stock_kis_api_failure.occurred_at     IS '발생 시각';

CREATE INDEX IF NOT EXISTS idx_stock_kis_api_failure_occurred
    ON tb_stock_kis_api_failure (occurred_at DESC);


-- ---------------------------------------------
-- ETF NAV 일별 (FHPST02440200 nav-comparison-daily-trend). 종가 vs NAV 괴리율. 대상은 마스터 활성 ETF(EF)만이며
-- ETN 은 마스터 ticker 가 Q 접두 7자라 제외한다. 1회 100건·연속조회 없음 → 일봉과 같은 날짜 창 백필.
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_etf_nav_daily
(
    ticker             VARCHAR(10)    NOT NULL,
    trade_date         DATE           NOT NULL,
    close_price        NUMERIC(18,2)  NOT NULL,
    prev_diff          NUMERIC(18,2)           DEFAULT NULL,
    prev_diff_sign     VARCHAR(1)              DEFAULT NULL,
    change_rate        NUMERIC(8,4)            DEFAULT NULL,
    volume             BIGINT                  DEFAULT NULL,
    nav                NUMERIC(18,4)           DEFAULT NULL,
    nav_prev_diff      NUMERIC(18,4)           DEFAULT NULL,
    nav_prev_diff_sign VARCHAR(1)              DEFAULT NULL,
    nav_change_rate    NUMERIC(8,4)            DEFAULT NULL,
    nav_diff           NUMERIC(18,4)           DEFAULT NULL,
    disparity_rate     NUMERIC(8,4)            DEFAULT NULL,
    collected_at       TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_etf_nav_daily PRIMARY KEY (ticker, trade_date)
);

COMMENT ON TABLE  tb_stock_etf_nav_daily                    IS 'ETF NAV 일별 (종가·NAV·괴리율). 대상은 활성 ETF(EF), ETN 제외';
COMMENT ON COLUMN tb_stock_etf_nav_daily.ticker             IS '단축 종목코드 (ETF)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.trade_date         IS '거래일 (stck_bsop_date)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.close_price        IS '종가 (원, stck_clpr)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.prev_diff          IS '전일 대비 (원, prdy_vrss)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.prev_diff_sign     IS '전일 대비 부호 (prdy_vrss_sign)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.change_rate        IS '전일 대비율 (%, prdy_ctrt)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.volume             IS '누적 거래량 (acml_vol)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.nav                IS 'NAV (원, nav)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.nav_prev_diff      IS 'NAV 전일 대비 (nav_prdy_vrss)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.nav_prev_diff_sign IS 'NAV 전일 대비 부호 (nav_prdy_vrss_sign)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.nav_change_rate    IS 'NAV 전일 대비율 (%, nav_prdy_ctrt)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.nav_diff           IS 'NAV 대비 현재가 차이 (원, nav_vrss_prpr)';
COMMENT ON COLUMN tb_stock_etf_nav_daily.disparity_rate     IS '괴리율 (%, dprt). 부호 규약은 실측 항목';
COMMENT ON COLUMN tb_stock_etf_nav_daily.collected_at       IS '마지막 수집 시각';

CREATE INDEX IF NOT EXISTS idx_stock_etf_nav_daily_date
    ON tb_stock_etf_nav_daily (trade_date);


-- ---------------------------------------------
-- 시장별 투자자매매동향 일별 (FHPTJ04040000). KOSPI/KOSDAQ 단위 투자자 15주체 순매수 대금·수량.
-- 백필은 tb_stock_index_daily(0001) 영업일 집합을 역순으로 돌며 기준일 1회 호출 (연속조회 없음). 금액 단위는 실측 항목.
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_market_investor_daily
(
    market_type              VARCHAR(10)    NOT NULL,
    trade_date               DATE           NOT NULL,
    foreign_net_amt          BIGINT                  DEFAULT NULL,
    foreign_net_qty          BIGINT                  DEFAULT NULL,
    foreign_reg_net_amt      BIGINT                  DEFAULT NULL,
    foreign_reg_net_qty      BIGINT                  DEFAULT NULL,
    foreign_nreg_net_amt     BIGINT                  DEFAULT NULL,
    foreign_nreg_net_qty     BIGINT                  DEFAULT NULL,
    individual_net_amt       BIGINT                  DEFAULT NULL,
    individual_net_qty       BIGINT                  DEFAULT NULL,
    institution_net_amt      BIGINT                  DEFAULT NULL,
    institution_net_qty      BIGINT                  DEFAULT NULL,
    securities_net_amt       BIGINT                  DEFAULT NULL,
    securities_net_qty       BIGINT                  DEFAULT NULL,
    invest_trust_net_amt     BIGINT                  DEFAULT NULL,
    invest_trust_net_qty     BIGINT                  DEFAULT NULL,
    private_fund_net_amt     BIGINT                  DEFAULT NULL,
    private_fund_net_qty     BIGINT                  DEFAULT NULL,
    bank_net_amt             BIGINT                  DEFAULT NULL,
    bank_net_qty             BIGINT                  DEFAULT NULL,
    insurance_net_amt        BIGINT                  DEFAULT NULL,
    insurance_net_qty        BIGINT                  DEFAULT NULL,
    merchant_bank_net_amt    BIGINT                  DEFAULT NULL,
    merchant_bank_net_qty    BIGINT                  DEFAULT NULL,
    pension_net_amt          BIGINT                  DEFAULT NULL,
    pension_net_qty          BIGINT                  DEFAULT NULL,
    other_net_amt            BIGINT                  DEFAULT NULL,
    other_net_qty            BIGINT                  DEFAULT NULL,
    other_org_net_amt        BIGINT                  DEFAULT NULL,
    other_org_net_qty        BIGINT                  DEFAULT NULL,
    other_corp_net_amt       BIGINT                  DEFAULT NULL,
    other_corp_net_qty       BIGINT                  DEFAULT NULL,
    collected_at             TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_market_investor_daily PRIMARY KEY (market_type, trade_date)
);

COMMENT ON TABLE  tb_stock_market_investor_daily                          IS '시장별(KOSPI|KOSDAQ) 투자자 15주체 순매수 대금·수량 일별 (FHPTJ04040000)';
COMMENT ON COLUMN tb_stock_market_investor_daily.market_type              IS '시장 구분: KOSPI | KOSDAQ (KIS 파라미터 KSP | KSQ)';
COMMENT ON COLUMN tb_stock_market_investor_daily.trade_date               IS '영업일 (stck_bsop_date)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_net_amt          IS '외국인 순매수 대금 (frgn_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_net_qty          IS '외국인 순매수 수량 (frgn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_reg_net_amt      IS '외국인 등록 순매수 대금 (frgn_reg_ntby_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_reg_net_qty      IS '외국인 등록 순매수 수량 (frgn_reg_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_nreg_net_amt     IS '외국인 비등록 순매수 대금 (frgn_nreg_ntby_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.foreign_nreg_net_qty     IS '외국인 비등록 순매수 수량 (frgn_nreg_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.individual_net_amt       IS '개인 순매수 대금 (prsn_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.individual_net_qty       IS '개인 순매수 수량 (prsn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.institution_net_amt      IS '기관계 순매수 대금 (orgn_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.institution_net_qty      IS '기관계 순매수 수량 (orgn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.securities_net_amt       IS '증권(금융투자) 순매수 대금 (scrt_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.securities_net_qty       IS '증권(금융투자) 순매수 수량 (scrt_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.invest_trust_net_amt     IS '투자신탁 순매수 대금 (ivtr_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.invest_trust_net_qty     IS '투자신탁 순매수 수량 (ivtr_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.private_fund_net_amt     IS '사모펀드 순매수 대금 (pe_fund_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.private_fund_net_qty     IS '사모펀드 순매수 수량 (pe_fund_ntby_vol)';
COMMENT ON COLUMN tb_stock_market_investor_daily.bank_net_amt             IS '은행 순매수 대금 (bank_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.bank_net_qty             IS '은행 순매수 수량 (bank_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.insurance_net_amt        IS '보험 순매수 대금 (insu_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.insurance_net_qty        IS '보험 순매수 수량 (insu_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.merchant_bank_net_amt    IS '종금 순매수 대금 (mrbn_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.merchant_bank_net_qty    IS '종금 순매수 수량 (mrbn_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.pension_net_amt          IS '기금(연기금) 순매수 대금 (fund_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.pension_net_qty          IS '기금(연기금) 순매수 수량 (fund_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_net_amt            IS '기타 순매수 대금 (etc_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_net_qty            IS '기타 순매수 수량 (etc_ntby_qty)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_org_net_amt        IS '기타 단체 순매수 대금 (etc_orgt_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_org_net_qty        IS '기타 단체 순매수 수량 (etc_orgt_ntby_vol)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_corp_net_amt       IS '기타 법인 순매수 대금 (etc_corp_ntby_tr_pbmn)';
COMMENT ON COLUMN tb_stock_market_investor_daily.other_corp_net_qty       IS '기타 법인 순매수 수량 (etc_corp_ntby_vol)';
COMMENT ON COLUMN tb_stock_market_investor_daily.collected_at             IS '마지막 수집 시각';


-- ---------------------------------------------
-- 종목 일별 지표 테이블 (2026-09-08, mv_stock_daily_metric 에서 전환). 전체 재계산이 25분(work_mem 512MB) 이라 MV 대신 테이블에
-- 최근 N일만 다시 계산해 upsert 한다 (DerivedViewRefresher.recomputeDailyMetric). 계산식은 그 SQL 이 단일 출처다.
-- 수익률 1/5/20/60/120, 이동평균 5/20/60/120, 이격도, 52주 고점(252거래일), 거래대금 5/60일, 외인·기관 5일 누적. 전부 수정주가 기준.
-- ---------------------------------------------
CREATE TABLE IF NOT EXISTS tb_stock_daily_metric
(
    ticker               VARCHAR(10)             NOT NULL,
    trade_date           DATE                    NOT NULL,
    adj_close            DOUBLE PRECISION        DEFAULT NULL,
    ret_1d               DOUBLE PRECISION        DEFAULT NULL,
    ret_5d               DOUBLE PRECISION        DEFAULT NULL,
    ret_20d              DOUBLE PRECISION        DEFAULT NULL,
    ret_60d              DOUBLE PRECISION        DEFAULT NULL,
    ret_120d             DOUBLE PRECISION        DEFAULT NULL,
    ma_5                 DOUBLE PRECISION        DEFAULT NULL,
    ma_20                DOUBLE PRECISION        DEFAULT NULL,
    ma_60                DOUBLE PRECISION        DEFAULT NULL,
    ma_120               DOUBLE PRECISION        DEFAULT NULL,
    dist_ma20            DOUBLE PRECISION        DEFAULT NULL,
    dist_ma60            DOUBLE PRECISION        DEFAULT NULL,
    high_52w             DOUBLE PRECISION        DEFAULT NULL,
    dist_high_52w        DOUBLE PRECISION        DEFAULT NULL,
    tv_avg_5d            DOUBLE PRECISION        DEFAULT NULL,
    tv_avg_60d           DOUBLE PRECISION        DEFAULT NULL,
    tv_ratio_5_60        DOUBLE PRECISION        DEFAULT NULL,
    vol_avg_20d          DOUBLE PRECISION        DEFAULT NULL,
    foreign_net_5d       DOUBLE PRECISION        DEFAULT NULL,
    institution_net_5d   DOUBLE PRECISION        DEFAULT NULL,
    computed_at          TIMESTAMPTZ(6)          NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_daily_metric PRIMARY KEY (ticker, trade_date)
);

COMMENT ON TABLE  tb_stock_daily_metric                    IS '종목 일별 지표 (수정주가 기준). DAILY 는 최근 kis.derived.metric-recompute-days 만, WEEKLY 는 전체 재계산';
COMMENT ON COLUMN tb_stock_daily_metric.adj_close          IS '수정 종가';
COMMENT ON COLUMN tb_stock_daily_metric.ret_120d           IS '120거래일 수익률 (ret_1d/5d/20d/60d 동일 규칙)';
COMMENT ON COLUMN tb_stock_daily_metric.ma_120             IS '120거래일 이동평균 (ma_5/20/60 동일 규칙)';
COMMENT ON COLUMN tb_stock_daily_metric.dist_ma20          IS '20일선 이격도 = 종가/MA20 − 1';
COMMENT ON COLUMN tb_stock_daily_metric.high_52w           IS '최근 252거래일 수정 고가 최대';
COMMENT ON COLUMN tb_stock_daily_metric.dist_high_52w      IS '52주 고점 대비 = 종가/high_52w − 1';
COMMENT ON COLUMN tb_stock_daily_metric.tv_ratio_5_60      IS '거래대금 5일 평균 / 60일 평균';
COMMENT ON COLUMN tb_stock_daily_metric.foreign_net_5d     IS '외국인 순매수 5일 누적 (tb_stock_investor_daily)';
COMMENT ON COLUMN tb_stock_daily_metric.computed_at        IS '마지막 계산 시각';

CREATE INDEX IF NOT EXISTS idx_stock_daily_metric_date
    ON tb_stock_daily_metric (trade_date);

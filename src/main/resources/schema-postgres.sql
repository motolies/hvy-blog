-- =============================================
-- PostgreSQL 18 스키마 (MariaDB schema.sql 변환)
-- =============================================
-- 변환 규칙:
--   bigint auto_increment → BIGINT GENERATED ALWAYS AS IDENTITY
--   bit                   → BOOLEAN
--   longtext              → TEXT
--   datetime(6)           → TIMESTAMPTZ(6)
--   int                   → INTEGER
--   decimal               → NUMERIC
--   camelCase 컬럼명      → snake_case (PG unquoted 식별자 소문자 폴딩 + JPA PhysicalNamingStrategy 대응)
--   comment '...' (인라인)→ COMMENT ON ... (별도 문장, 각 테이블 생성 직후 배치)
-- =============================================
-- 시각 타입 규칙(2026-07-13 갱신):
--   모든 시각 컬럼은 TIMESTAMPTZ(시간대 인지) 사용. DB는 UTC 기준으로 운영한다.
--   과거 timestamp without time zone(UTC 벽시계 저장)에서 timestamptz로 전환한 이력은
--   db/migration_timestamptz/V20260713_01__convert_timestamptz.sql 참조.
--   DATE 타입(tb_jira_issue.start_date/end_date)은 시각 정보가 없어 그대로 유지한다.
-- =============================================

-- =============================================
-- DROP (FK 역순, CASCADE로 의존 객체 포함 제거)
-- =============================================

DROP TABLE IF EXISTS tb_memo CASCADE;
DROP TABLE IF EXISTS tb_memo_category CASCADE;
DROP TABLE IF EXISTS tb_jira_worklog CASCADE;
DROP TABLE IF EXISTS tb_jira_issue CASCADE;
DROP TABLE IF EXISTS tb_master_code CASCADE;
DROP TABLE IF EXISTS tb_user_authority_map CASCADE;
DROP TABLE IF EXISTS tb_user CASCADE;
DROP TABLE IF EXISTS tb_series_post CASCADE;
DROP TABLE IF EXISTS tb_series CASCADE;
DROP TABLE IF EXISTS tb_post_draft CASCADE;
DROP TABLE IF EXISTS tb_post_tag_map CASCADE;
DROP TABLE IF EXISTS tb_tag CASCADE;
DROP TABLE IF EXISTS tb_search_engine CASCADE;
DROP TABLE IF EXISTS tb_file CASCADE;
DROP TABLE IF EXISTS tb_post CASCADE;
DROP TABLE IF EXISTS tb_category CASCADE;
DROP TABLE IF EXISTS tb_authority CASCADE;
DROP TABLE IF EXISTS tb_system_log CASCADE;
DROP TABLE IF EXISTS tb_api_log CASCADE;
DROP TABLE IF EXISTS shedlock CASCADE;

-- AI 시장 판단(advisor) 모듈 (생성 역순, 2026-09-13). stock 과는 FK 가 없고 ticker 는 값 참조
DROP TABLE IF EXISTS tb_advisor_prompt_input CASCADE;
DROP TABLE IF EXISTS tb_advisor_intraday_check CASCADE;
DROP TABLE IF EXISTS tb_advisor_lesson CASCADE;
DROP TABLE IF EXISTS tb_advisor_signal_ic_daily CASCADE;
DROP TABLE IF EXISTS tb_advisor_call_score CASCADE;
DROP TABLE IF EXISTS tb_advisor_candidate_score CASCADE;
DROP TABLE IF EXISTS tb_advisor_pick CASCADE;
DROP TABLE IF EXISTS tb_advisor_candidate CASCADE;
DROP TABLE IF EXISTS tb_advisor_advice CASCADE;
DROP TABLE IF EXISTS tb_advisor_signal_weight CASCADE;
DROP TABLE IF EXISTS tb_advisor_weight_set CASCADE;
DROP TABLE IF EXISTS tb_advisor_run CASCADE;

-- 주식(KIS) 수집 모듈 (생성 역순). CASCADE 가 파생 객체도 함께 제거한다:
--   mv_stock_adjust_factor, vw_stock_daily_price_adj, vw_stock_market_calendar,
--   mv_stock_daily_metric, mv_stock_index_metric, mv_stock_sector_daily, vw_stock_universe_daily
DROP TABLE IF EXISTS tb_stock_daily_metric CASCADE;
DROP TABLE IF EXISTS tb_stock_market_investor_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_etf_nav_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_kis_api_failure CASCADE;
DROP TABLE IF EXISTS tb_stock_kis_token CASCADE;
DROP TABLE IF EXISTS tb_stock_collect_checkpoint CASCADE;
DROP TABLE IF EXISTS tb_stock_collect_run CASCADE;
DROP TABLE IF EXISTS tb_stock_global_sector_map CASCADE;
DROP TABLE IF EXISTS tb_stock_global_market_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_sector_map CASCADE;
DROP TABLE IF EXISTS tb_stock_financial CASCADE;
DROP TABLE IF EXISTS tb_stock_adjust_event CASCADE;
DROP TABLE IF EXISTS tb_stock_corporate_action CASCADE;
DROP TABLE IF EXISTS tb_stock_market_stat_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_investor_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_valuation_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_daily_price CASCADE;
DROP TABLE IF EXISTS tb_stock_index_daily CASCADE;
DROP TABLE IF EXISTS tb_stock_index_master CASCADE;
DROP TABLE IF EXISTS tb_stock_market_holiday CASCADE;
DROP TABLE IF EXISTS tb_stock_master_history CASCADE;
DROP TABLE IF EXISTS tb_stock_master CASCADE;

-- =============================================
-- CREATE TABLE
-- =============================================

CREATE TABLE tb_authority
(
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NULL,
    CONSTRAINT uk_authority_name UNIQUE (name)
);
COMMENT ON TABLE  tb_authority      IS '권한 정보';
COMMENT ON COLUMN tb_authority.id   IS '권한 ID';
COMMENT ON COLUMN tb_authority.name IS '권한명';

-- ---------------------------------------------

CREATE TABLE tb_category
(
    id        VARCHAR(32)  NOT NULL PRIMARY KEY,
    name      VARCHAR(64)  NOT NULL,
    seq       INTEGER      NOT NULL,
    full_name VARCHAR(512) NOT NULL,
    full_path VARCHAR(512) NOT NULL,
    parent_id VARCHAR(32)  NULL,
    CONSTRAINT fk_parent_id FOREIGN KEY (parent_id) REFERENCES tb_category (id)
);
COMMENT ON TABLE  tb_category           IS '카테고리 계층 구조';
COMMENT ON COLUMN tb_category.id        IS '카테고리 ID';
COMMENT ON COLUMN tb_category.name      IS '카테고리명';
COMMENT ON COLUMN tb_category.seq       IS '정렬 순서';
COMMENT ON COLUMN tb_category.full_name IS '전체 카테고리명 (루트부터 현재까지)';
COMMENT ON COLUMN tb_category.full_path IS '전체 카테고리 경로';
COMMENT ON COLUMN tb_category.parent_id IS '부모 카테고리 ID (최상위는 NULL)';

-- ---------------------------------------------

CREATE TABLE tb_post
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject       VARCHAR(512) NOT NULL,
    body          TEXT         NOT NULL,
    normal_body   TEXT         NOT NULL,
    public_access BOOLEAN      NOT NULL,
    main_page     BOOLEAN      NOT NULL,
    view_count    INTEGER      NOT NULL,
    status        VARCHAR(3)   NOT NULL DEFAULT 'PUB',
    category_id   VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ(6) NOT NULL,
    created_by    VARCHAR(255) NULL,
    updated_at    TIMESTAMPTZ(6) NOT NULL,
    updated_by    VARCHAR(255) NULL,
    CONSTRAINT fk_post_category_id FOREIGN KEY (category_id) REFERENCES tb_category (id)
);
COMMENT ON TABLE  tb_post               IS '블로그 포스트';
COMMENT ON COLUMN tb_post.id            IS '포스트 ID';
COMMENT ON COLUMN tb_post.subject       IS '제목';
COMMENT ON COLUMN tb_post.body          IS '본문 (마크다운)';
COMMENT ON COLUMN tb_post.normal_body   IS '본문 일반 텍스트 (검색용)';
COMMENT ON COLUMN tb_post.public_access IS '공개 여부';
COMMENT ON COLUMN tb_post.main_page     IS '메인 페이지 노출 여부';
COMMENT ON COLUMN tb_post.view_count    IS '조회수';
COMMENT ON COLUMN tb_post.status        IS '게시 상태 (TEM=임시저장, PUB=배포완료)';
COMMENT ON COLUMN tb_post.category_id   IS '카테고리 ID (FK)';
COMMENT ON COLUMN tb_post.created_at    IS '생성일시';
COMMENT ON COLUMN tb_post.created_by    IS '생성자';
COMMENT ON COLUMN tb_post.updated_at    IS '수정일시';
COMMENT ON COLUMN tb_post.updated_by    IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_post_draft
(
    post_id    BIGINT PRIMARY KEY REFERENCES tb_post (id) ON DELETE CASCADE,
    subject    VARCHAR(512) NOT NULL,
    body       TEXT         NOT NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL,
    updated_by VARCHAR(255) NULL
);
COMMENT ON TABLE  tb_post_draft            IS '발행 포스트의 임시저장 초안';
COMMENT ON COLUMN tb_post_draft.post_id    IS '포스��� ID (FK, PK)';
COMMENT ON COLUMN tb_post_draft.subject    IS '초안 제목';
COMMENT ON COLUMN tb_post_draft.body       IS '초안 본문';
COMMENT ON COLUMN tb_post_draft.updated_at IS '수정일시';
COMMENT ON COLUMN tb_post_draft.updated_by IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_file
(
    id          BIGINT       NOT NULL PRIMARY KEY,
    post_id     BIGINT       NOT NULL,
    origin_name VARCHAR(256) NOT NULL,
    type        VARCHAR(512) NOT NULL,
    path        VARCHAR(512) NOT NULL,
    file_size   BIGINT       NOT NULL,
    deleted     BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ(6) NOT NULL,
    created_by  VARCHAR(255) NULL,
    CONSTRAINT fk_file_post FOREIGN KEY (post_id) REFERENCES tb_post (id)
);
COMMENT ON TABLE  tb_file             IS '첨부 파일';
COMMENT ON COLUMN tb_file.id          IS '파일 ID (TSID)';
COMMENT ON COLUMN tb_file.post_id     IS '포스트 ID (FK)';
COMMENT ON COLUMN tb_file.origin_name IS '원본 파일명';
COMMENT ON COLUMN tb_file.type        IS '파일 MIME 타입';
COMMENT ON COLUMN tb_file.path        IS '저장 경로';
COMMENT ON COLUMN tb_file.file_size   IS '파일 크기 (bytes)';
COMMENT ON COLUMN tb_file.deleted     IS '삭제 여부';
COMMENT ON COLUMN tb_file.created_at  IS '생성일시';
COMMENT ON COLUMN tb_file.created_by  IS '생성자';

-- ---------------------------------------------

CREATE TABLE tb_search_engine
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(32)  NOT NULL,
    url        VARCHAR(512) NOT NULL,
    seq        INTEGER      NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL,
    created_by VARCHAR(255) NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL,
    updated_by VARCHAR(255) NULL
);
COMMENT ON TABLE  tb_search_engine            IS '검색 엔진 설정';
COMMENT ON COLUMN tb_search_engine.id         IS '검색 엔진 ID';
COMMENT ON COLUMN tb_search_engine.name       IS '검색 엔진명';
COMMENT ON COLUMN tb_search_engine.url        IS '검색 URL 패턴';
COMMENT ON COLUMN tb_search_engine.seq        IS '정렬 순서';
COMMENT ON COLUMN tb_search_engine.created_at IS '생성일시';
COMMENT ON COLUMN tb_search_engine.created_by IS '생성자';
COMMENT ON COLUMN tb_search_engine.updated_at IS '수정일시';
COMMENT ON COLUMN tb_search_engine.updated_by IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_tag
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL,
    created_by VARCHAR(255) NULL,
    CONSTRAINT uk_tag_name UNIQUE (name)
);
COMMENT ON TABLE  tb_tag            IS '태그';
COMMENT ON COLUMN tb_tag.id         IS '태그 ID';
COMMENT ON COLUMN tb_tag.name       IS '태그명';
COMMENT ON COLUMN tb_tag.created_at IS '생성일시';
COMMENT ON COLUMN tb_tag.created_by IS '생성자';

-- ---------------------------------------------

CREATE TABLE tb_post_tag_map
(
    post_id BIGINT NOT NULL,
    tag_id  BIGINT NOT NULL,
    PRIMARY KEY (post_id, tag_id),
    CONSTRAINT fk_post_tag_map_post_id FOREIGN KEY (post_id) REFERENCES tb_post (id),
    CONSTRAINT fk_post_tag_map_tag_id  FOREIGN KEY (tag_id)  REFERENCES tb_tag (id)
);
COMMENT ON TABLE  tb_post_tag_map         IS '포스트-태그 매핑';
COMMENT ON COLUMN tb_post_tag_map.post_id IS '포스트 ID (FK)';
COMMENT ON COLUMN tb_post_tag_map.tag_id  IS '태그 ID (FK)';

-- ---------------------------------------------

CREATE TABLE tb_series
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(256) NOT NULL,
    description VARCHAR(1024) NULL,
    created_at  TIMESTAMPTZ(6) NOT NULL,
    created_by  VARCHAR(255) NULL,
    updated_at  TIMESTAMPTZ(6) NOT NULL,
    updated_by  VARCHAR(255) NULL
);
COMMENT ON TABLE  tb_series             IS '시리즈(연재물)';
COMMENT ON COLUMN tb_series.id          IS '시리즈 ID';
COMMENT ON COLUMN tb_series.title       IS '시리즈 제목';
COMMENT ON COLUMN tb_series.description IS '시리즈 설명';
COMMENT ON COLUMN tb_series.created_at  IS '생성일시';
COMMENT ON COLUMN tb_series.created_by  IS '생성자';
COMMENT ON COLUMN tb_series.updated_at  IS '수정일시';
COMMENT ON COLUMN tb_series.updated_by  IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_series_post
(
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    series_id BIGINT  NOT NULL,
    post_id   BIGINT  NOT NULL,
    seq       INTEGER NOT NULL,
    CONSTRAINT fk_series_post_series_id FOREIGN KEY (series_id) REFERENCES tb_series (id),
    CONSTRAINT fk_series_post_post_id FOREIGN KEY (post_id) REFERENCES tb_post (id),
    CONSTRAINT uk_series_post UNIQUE (series_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx01_series_post ON tb_series_post (series_id, seq);
COMMENT ON TABLE  tb_series_post           IS '시리즈-포스트 매핑';
COMMENT ON COLUMN tb_series_post.id        IS '매핑 ID';
COMMENT ON COLUMN tb_series_post.series_id IS '시리즈 ID (FK)';
COMMENT ON COLUMN tb_series_post.post_id   IS '포스트 ID (FK)';
COMMENT ON COLUMN tb_series_post.seq       IS '시리즈 내 순서';

-- ---------------------------------------------

CREATE TABLE tb_user
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    login_id   VARCHAR(32)  NOT NULL,
    password   VARCHAR(64)  NOT NULL,
    is_enabled BOOLEAN      NULL,
    CONSTRAINT uk_user_login_id UNIQUE (login_id)
);
COMMENT ON TABLE  tb_user               IS '사용자 계정';
COMMENT ON COLUMN tb_user.id            IS '사용자 ID';
COMMENT ON COLUMN tb_user.name          IS '사용자명';
COMMENT ON COLUMN tb_user.login_id      IS '로그인 ID';
COMMENT ON COLUMN tb_user.password      IS '비밀번호 (암호화)';
COMMENT ON COLUMN tb_user.is_enabled    IS '계정 활성화 여부';

-- ---------------------------------------------

CREATE TABLE tb_user_authority_map
(
    user_id      BIGINT NOT NULL,
    authority_id BIGINT NOT NULL,
    PRIMARY KEY (authority_id, user_id),
    CONSTRAINT fk_user_authority_map_authority_id FOREIGN KEY (authority_id) REFERENCES tb_authority (id),
    CONSTRAINT fk_user_authority_map_user_id      FOREIGN KEY (user_id)      REFERENCES tb_user (id)
);
COMMENT ON TABLE  tb_user_authority_map              IS '사용자-권한 매핑';
COMMENT ON COLUMN tb_user_authority_map.user_id      IS '사용자 ID (FK)';
COMMENT ON COLUMN tb_user_authority_map.authority_id IS '권한 ID (FK)';

-- ---------------------------------------------
-- 마스터코드 관리 테이블 (자기참조 트리 + JSONB 속성)
-- ---------------------------------------------

CREATE TABLE tb_master_code
(
    -- TSID 문자열(Crockford Base32 13자). IDENTITY 를 쓰지 않는 이유는 path 때문이다 —
    -- path 가 '/부모path/자기id' 라서 INSERT 전에 id 를 알아야 한다. IDENTITY 는 INSERT 후에야
    -- id 가 정해져 path 를 뒤따르는 UPDATE 로 채워야 했고, 그 UPDATE 를 빠뜨리면 path 가 NULL 로
    -- 남아 서브트리 조회가 통째로 죽었다. 애플리케이션이 id 를 만들면 path 를 같은 INSERT 에 넣을 수
    -- 있어 아래 NOT NULL 이 그 사고를 DB 레벨에서 거부한다. (tb_category 가 쓰는 방식과 같다.)
    id               VARCHAR(13)  NOT NULL PRIMARY KEY,

    -- 트리 구조
    parent_id        VARCHAR(13)  NULL,
    depth            INTEGER      NOT NULL DEFAULT 0,
    -- ★ NOT NULL. 서브트리 조회(findSubtree)가 전적으로 이 값에 의존한다.
    --   세그먼트가 14자(구분자 포함)라 512 안에 36단계까지 들어간다.
    path             VARCHAR(512) NOT NULL,

    -- 코드 정보
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512) NULL,

    -- 유연한 속성 (JSONB)
    attributes       JSONB        NOT NULL DEFAULT '{}'::JSONB,
    attribute_schema JSONB        NOT NULL DEFAULT '[]'::JSONB,

    -- 메타
    sort             INTEGER      NOT NULL DEFAULT 0,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,

    -- 감사
    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(64)  NULL,
    updated_at       TIMESTAMPTZ(6) NULL,
    updated_by       VARCHAR(64)  NULL,

    CONSTRAINT uk_master_code_parent_code UNIQUE (parent_id, code),
    CONSTRAINT fk_master_code_parent      FOREIGN KEY (parent_id) REFERENCES tb_master_code (id)
);
COMMENT ON TABLE  tb_master_code                   IS '마스터코드 (자기참조 트리 구조)';
COMMENT ON COLUMN tb_master_code.id                IS '마스터코드 ID (PK, TSID 13자)';
COMMENT ON COLUMN tb_master_code.parent_id         IS '부모 노드 ID (NULL이면 루트)';
COMMENT ON COLUMN tb_master_code.depth             IS '트리 깊이 (0=루트, 1+=하위)';
COMMENT ON COLUMN tb_master_code.path              IS 'Materialized Path (예: /0RF87Y7EXVPB9/0RF880A9HVQCF)';
COMMENT ON COLUMN tb_master_code.code              IS '코드값';
COMMENT ON COLUMN tb_master_code.name              IS '코드명';
COMMENT ON COLUMN tb_master_code.description       IS '설명';
COMMENT ON COLUMN tb_master_code.attributes        IS '코드별 속성값 (JSONB)';
COMMENT ON COLUMN tb_master_code.attribute_schema  IS '루트 노드 전용: 속성 스키마 정의 (JSONB)';
COMMENT ON COLUMN tb_master_code.sort              IS '정렬순서';
COMMENT ON COLUMN tb_master_code.is_active         IS '활성화 여부';
COMMENT ON COLUMN tb_master_code.created_at        IS '생성일시';
COMMENT ON COLUMN tb_master_code.created_by        IS '생성자';
COMMENT ON COLUMN tb_master_code.updated_at        IS '수정일시';
COMMENT ON COLUMN tb_master_code.updated_by        IS '수정자';

CREATE INDEX idx_mc_parent_id  ON tb_master_code (parent_id);
CREATE INDEX idx_mc_path       ON tb_master_code (path);
CREATE INDEX idx_mc_code       ON tb_master_code (code);
CREATE INDEX idx_mc_attributes ON tb_master_code USING GIN (attributes);

-- ---------------------------------------------
-- Jira 관련 테이블들
-- ---------------------------------------------

CREATE TABLE tb_jira_issue
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    jira_issue_id BIGINT       NOT NULL,
    issue_key     VARCHAR(32)  NOT NULL,
    issue_link    VARCHAR(512) NOT NULL,
    summary       VARCHAR(512) NOT NULL,
    issue_type    VARCHAR(64)  NULL,
    status        VARCHAR(64)  NULL,
    assignee      VARCHAR(128) NULL,
    components    VARCHAR(512) NULL,
    story_points  NUMERIC(5,2) NULL,
    start_date    DATE         NULL,
    end_date      DATE         NULL,
    sprint        VARCHAR(32)  NULL,
    created_at    TIMESTAMPTZ(6) NOT NULL,
    created_by    VARCHAR(32)  NULL,
    updated_at    TIMESTAMPTZ(6) NOT NULL,
    updated_by    VARCHAR(32)  NULL,
    CONSTRAINT uk_jira_issue_key UNIQUE (issue_key)
);
COMMENT ON TABLE  tb_jira_issue               IS 'Jira 이슈 정보';
COMMENT ON COLUMN tb_jira_issue.id            IS '이슈 내부 ID';
COMMENT ON COLUMN tb_jira_issue.jira_issue_id IS '지라 이슈 Id';
COMMENT ON COLUMN tb_jira_issue.issue_key     IS '지라 이슈 키 (예: PROJ-123)';
COMMENT ON COLUMN tb_jira_issue.issue_link    IS '지라 이슈 링크';
COMMENT ON COLUMN tb_jira_issue.summary       IS '이슈 요약';
COMMENT ON COLUMN tb_jira_issue.issue_type    IS '이슈 유형';
COMMENT ON COLUMN tb_jira_issue.status        IS '이슈 상태';
COMMENT ON COLUMN tb_jira_issue.assignee      IS '담당자';
COMMENT ON COLUMN tb_jira_issue.components    IS '컴포넌트 (쉼표로 구분)';
COMMENT ON COLUMN tb_jira_issue.story_points  IS '스토리 포인트';
COMMENT ON COLUMN tb_jira_issue.start_date    IS '시작일';
COMMENT ON COLUMN tb_jira_issue.end_date      IS '완료일 (Done 상태가 된 날짜)';
COMMENT ON COLUMN tb_jira_issue.sprint        IS '스프린트명';
COMMENT ON COLUMN tb_jira_issue.created_at    IS '생성일시';
COMMENT ON COLUMN tb_jira_issue.created_by    IS '생성자';
COMMENT ON COLUMN tb_jira_issue.updated_at    IS '수정일시';
COMMENT ON COLUMN tb_jira_issue.updated_by    IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_jira_worklog
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id   BIGINT       NOT NULL,
    issue_key  VARCHAR(32)  NOT NULL,
    issue_type VARCHAR(64)  NULL,
    status     VARCHAR(64)  NULL,
    issue_link VARCHAR(512) NOT NULL,
    summary    VARCHAR(512) NOT NULL,
    author     VARCHAR(128) NOT NULL,
    components VARCHAR(512) NULL,
    time_spent VARCHAR(32)  NOT NULL,
    time_hours NUMERIC(5,2) NOT NULL,
    comment    TEXT         NULL,
    started    TIMESTAMPTZ(6) NOT NULL,
    worklog_id VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL,
    created_by VARCHAR(32)  NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL,
    updated_by VARCHAR(32)  NULL,
    CONSTRAINT fk_jira_worklog_issue_id FOREIGN KEY (issue_id) REFERENCES tb_jira_issue (id),
    CONSTRAINT uk_jira_worklog_id UNIQUE (worklog_id)
);
COMMENT ON TABLE  tb_jira_worklog            IS 'Jira 워크로그 정보';
COMMENT ON COLUMN tb_jira_worklog.id         IS '워크로그 내부 ID';
COMMENT ON COLUMN tb_jira_worklog.issue_id   IS '이슈 ID (FK)';
COMMENT ON COLUMN tb_jira_worklog.issue_key  IS '지라 이슈 키';
COMMENT ON COLUMN tb_jira_worklog.issue_type IS '이슈 유형';
COMMENT ON COLUMN tb_jira_worklog.status     IS '이슈 상태';
COMMENT ON COLUMN tb_jira_worklog.issue_link IS '지라 이슈 링크';
COMMENT ON COLUMN tb_jira_worklog.summary    IS '이슈 요약';
COMMENT ON COLUMN tb_jira_worklog.author     IS '작업자';
COMMENT ON COLUMN tb_jira_worklog.components IS '컴포넌트 (쉼표로 구분)';
COMMENT ON COLUMN tb_jira_worklog.time_spent IS '소요 시간 (예: 2h 30m)';
COMMENT ON COLUMN tb_jira_worklog.time_hours IS '소요 시간(시간)';
COMMENT ON COLUMN tb_jira_worklog.comment    IS '작업 로그 코멘트';
COMMENT ON COLUMN tb_jira_worklog.started    IS '작업 시작 일시';
COMMENT ON COLUMN tb_jira_worklog.worklog_id IS 'Jira 워크로그 ID';
COMMENT ON COLUMN tb_jira_worklog.created_at IS '생성일시';
COMMENT ON COLUMN tb_jira_worklog.created_by IS '생성자';
COMMENT ON COLUMN tb_jira_worklog.updated_at IS '수정일시';
COMMENT ON COLUMN tb_jira_worklog.updated_by IS '수정자';

-- ---------------------------------------------
-- 메모 관련 테이블들
-- ---------------------------------------------

CREATE TABLE tb_memo_category
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(64)  NOT NULL,
    seq        INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ(6) NOT NULL,
    created_by VARCHAR(255) NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL,
    updated_by VARCHAR(255) NULL,
    CONSTRAINT uk_memo_category_name UNIQUE (name)
);
COMMENT ON TABLE  tb_memo_category            IS '메모 카테고리';
COMMENT ON COLUMN tb_memo_category.id         IS '메모 카테고리 ID';
COMMENT ON COLUMN tb_memo_category.name       IS '카테고리명';
COMMENT ON COLUMN tb_memo_category.seq        IS '정렬 순서';
COMMENT ON COLUMN tb_memo_category.created_at IS '생성일시';
COMMENT ON COLUMN tb_memo_category.created_by IS '생성자';
COMMENT ON COLUMN tb_memo_category.updated_at IS '수정일시';
COMMENT ON COLUMN tb_memo_category.updated_by IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_memo
(
    id          BIGINT       NOT NULL PRIMARY KEY,
    content     TEXT         NOT NULL,
    category_id BIGINT       NULL,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ(6) NOT NULL,
    created_by  VARCHAR(255) NULL,
    updated_at  TIMESTAMPTZ(6) NOT NULL,
    updated_by  VARCHAR(255) NULL,
    CONSTRAINT fk_memo_category FOREIGN KEY (category_id) REFERENCES tb_memo_category (id)
);
COMMENT ON TABLE  tb_memo             IS '메모';
COMMENT ON COLUMN tb_memo.id          IS '메모 ID (TSID)';
COMMENT ON COLUMN tb_memo.content     IS '메모 내용';
COMMENT ON COLUMN tb_memo.category_id IS '카테고리 ID (FK)';
COMMENT ON COLUMN tb_memo.deleted     IS '삭제 여부 (소프트 삭제)';
COMMENT ON COLUMN tb_memo.created_at  IS '생성일시';
COMMENT ON COLUMN tb_memo.created_by  IS '생성자';
COMMENT ON COLUMN tb_memo.updated_at  IS '수정일시';
COMMENT ON COLUMN tb_memo.updated_by  IS '수정자';

-- ---------------------------------------------
-- 시스템/API 로그 테이블 (hvy-common 엔티티 대응)
-- ---------------------------------------------

CREATE TABLE tb_system_log
(
    id               BIGINT        NOT NULL PRIMARY KEY,
    trace_id         VARCHAR(32)   NULL,
    span_id          VARCHAR(16)   NULL,
    request_uri      VARCHAR(1024) NULL,
    controller_name  VARCHAR(512)  NULL,
    method_name      VARCHAR(512)  NULL,
    http_method_type VARCHAR(8)    NULL,
    param_data       TEXT          NULL,
    response_body    TEXT          NULL,
    stack_trace      TEXT          NULL,
    remote_addr      VARCHAR(64)   NULL,
    process_time     BIGINT        NULL,
    status           VARCHAR(4)    NOT NULL,
    created_at       TIMESTAMPTZ(6)  NOT NULL,
    created_by       VARCHAR(255)  NULL
);
COMMENT ON TABLE  tb_system_log                  IS '시스템 로그 (요청/응답 추적)';
COMMENT ON COLUMN tb_system_log.id               IS '로그 ID (TSID)';
COMMENT ON COLUMN tb_system_log.trace_id         IS '트레이스 ID';
COMMENT ON COLUMN tb_system_log.span_id          IS '스팬 ID';
COMMENT ON COLUMN tb_system_log.request_uri      IS '요청 URI';
COMMENT ON COLUMN tb_system_log.controller_name  IS '컨트롤러명';
COMMENT ON COLUMN tb_system_log.method_name      IS '메서드명';
COMMENT ON COLUMN tb_system_log.http_method_type IS 'HTTP 메서드 유형';
COMMENT ON COLUMN tb_system_log.param_data       IS '요청 파라미터';
COMMENT ON COLUMN tb_system_log.response_body    IS '응답 본문';
COMMENT ON COLUMN tb_system_log.stack_trace      IS '스택 트레이스';
COMMENT ON COLUMN tb_system_log.remote_addr      IS '클라이언트 IP';
COMMENT ON COLUMN tb_system_log.process_time     IS '처리 시간 (ms)';
COMMENT ON COLUMN tb_system_log.status           IS '응답 상태 코드';
COMMENT ON COLUMN tb_system_log.created_at       IS '생성일시';
COMMENT ON COLUMN tb_system_log.created_by       IS '생성자';

-- ---------------------------------------------

CREATE TABLE tb_api_log
(
    id               BIGINT        NOT NULL PRIMARY KEY,
    trace_id         VARCHAR(32)   NULL,
    span_id          VARCHAR(16)   NULL,
    request_uri      VARCHAR(1024) NULL,
    http_method_type VARCHAR(8)    NULL,
    request_header   VARCHAR(8192) NULL,
    request_param    VARCHAR(4096) NULL,
    request_body     TEXT          NULL,
    response_status  VARCHAR(128)  NULL,
    response_body    TEXT          NULL,
    process_time     BIGINT        NULL,
    created_at       TIMESTAMPTZ(6)  NOT NULL,
    created_by       VARCHAR(255)  NULL
);
COMMENT ON TABLE  tb_api_log                   IS 'API 로그 (외부 API 호출 추적)';
COMMENT ON COLUMN tb_api_log.id                IS '로그 ID (TSID)';
COMMENT ON COLUMN tb_api_log.trace_id          IS '트레이스 ID';
COMMENT ON COLUMN tb_api_log.span_id           IS '스팬 ID';
COMMENT ON COLUMN tb_api_log.request_uri       IS '요청 URI';
COMMENT ON COLUMN tb_api_log.http_method_type  IS 'HTTP 메서드 유형';
COMMENT ON COLUMN tb_api_log.request_header    IS '요청 헤더';
COMMENT ON COLUMN tb_api_log.request_param     IS '요청 파라미터';
COMMENT ON COLUMN tb_api_log.request_body      IS '요청 본문';
COMMENT ON COLUMN tb_api_log.response_status   IS '응답 상태';
COMMENT ON COLUMN tb_api_log.response_body     IS '응답 본문';
COMMENT ON COLUMN tb_api_log.process_time      IS '처리 시간 (ms)';
COMMENT ON COLUMN tb_api_log.created_at        IS '생성일시';
COMMENT ON COLUMN tb_api_log.created_by        IS '생성자';

-- ---------------------------------------------
-- ShedLock 분산 스케줄러 잠금 테이블
-- ---------------------------------------------

CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ(3) NOT NULL,
    locked_at  TIMESTAMPTZ(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
COMMENT ON TABLE shedlock IS 'ShedLock 분산 스케줄러 잠금 테이블';

-- =============================================
-- INDEX
-- =============================================

CREATE INDEX idx_memo_deleted_created ON tb_memo (deleted, created_at DESC);
CREATE INDEX idx_memo_category        ON tb_memo (category_id, deleted);

CREATE INDEX idx_system_log_trace_id    ON tb_system_log (trace_id);
CREATE INDEX idx_system_log_span_id     ON tb_system_log (span_id);
CREATE INDEX idx_system_log_created_at  ON tb_system_log (created_at);
CREATE INDEX idx_system_log_request_uri ON tb_system_log (request_uri);

CREATE INDEX idx_api_log_trace_id    ON tb_api_log (trace_id);
CREATE INDEX idx_api_log_span_id     ON tb_api_log (span_id);
CREATE INDEX idx_api_log_created_at  ON tb_api_log (created_at);
CREATE INDEX idx_api_log_request_uri ON tb_api_log (request_uri);

-- 관리자 대시보드 집계 전용 인덱스 (db/migration_dashboard/V20260828_01__admin_dashboard.sql 와 동기화)
-- [1] 조회 beacon 부분 인덱스 — 아래 3개 조건을 쿼리 WHERE 에 문자열 그대로 넣어야 플래너가 선택한다
CREATE INDEX idx_system_log_post_view ON tb_system_log (created_at)
 WHERE status = 'SUCC'
   AND http_method_type = 'POST'
   AND request_uri LIKE '/api/post/%/view';
-- [2] 최근 에러 N건 — FAIL 은 극소수라 인덱스 스캔 후 LIMIT 에서 즉시 종료
CREATE INDEX idx_system_log_status_created ON tb_system_log (status, created_at DESC);

-- 한글, 영문대소문자 검색을 위한 gin_bigm_ops 적용
-- LOWER(col) LIKE LOWER(?) 패턴으로 검색
CREATE INDEX idx_post_subject_bigm ON tb_post USING GIN (LOWER(subject) gin_bigm_ops);
CREATE INDEX idx_post_normal_body_bigm ON tb_post USING GIN (LOWER(normal_body) gin_bigm_ops);

CREATE INDEX idx_category_name_bigm ON tb_category USING GIN (LOWER(name) gin_bigm_ops);

CREATE INDEX idx_tag_name_bigm ON tb_tag USING GIN (LOWER(name) gin_bigm_ops);

CREATE INDEX idx_memo_content_bigm ON tb_memo USING GIN (LOWER(content) gin_bigm_ops);

CREATE INDEX idx_hot_deal_item_title_bigm ON tb_hot_deal_item USING GIN (LOWER(title) gin_bigm_ops);


-- =============================================
-- 주식(KIS) 수집 모듈
-- 원본은 db/stock-schema.sql → db/stock-derived.sql → db/stock-seed.sql (이 순서로 psql 적용).
-- 아래 세 블록은 원본의 원문 복사본이다. 수정은 원본 파일에만 하고 여기로 다시 복사한다.
-- StockSchemaSyncTest 가 BEGIN/END 마커 사이가 원본과 줄 단위로 같은지, DROP 블록에 테이블 전부가 있는지 검사한다.
-- =============================================

-- >>> BEGIN db/stock-schema.sql
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
    change_rate        NUMERIC(12,4)           DEFAULT NULL,
    volume             BIGINT                  DEFAULT NULL,
    nav                NUMERIC(18,4)           DEFAULT NULL,
    nav_prev_diff      NUMERIC(18,4)           DEFAULT NULL,
    nav_prev_diff_sign VARCHAR(1)              DEFAULT NULL,
    nav_change_rate    NUMERIC(12,4)           DEFAULT NULL,
    nav_diff           NUMERIC(18,4)           DEFAULT NULL,
    disparity_rate     NUMERIC(12,4)           DEFAULT NULL,
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
COMMENT ON COLUMN tb_stock_etf_nav_daily.disparity_rate     IS '괴리율 (%, dprt). NAV 가 0/누락이면 KIS 가 10^4 이상 값을 주므로 |값| >= 10000 은 NAV 없음으로 취급 (2026-09-09 265690)';
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
-- <<< END db/stock-schema.sql

-- >>> BEGIN db/stock-derived.sql
-- =============================================
-- 주식 파생 계층 (수정주가 계수 MV · 소비자용 뷰)
-- PostgreSQL. db/stock-schema.sql 적용 후 psql 로 실행한다. 갱신은 ADJUST_FACTOR / DERIVED_REFRESH 잡이 수행한다.
-- =============================================
-- 원주가(tb_stock_daily_price)는 그대로 두고 기업행사에서 파생한 계수(tb_stock_adjust_event)를 조회 시점에 곱한다.
-- KIS 수정주가는 조회 시점마다 재계산되어 저장하면 재현할 수 없기 때문이다.
-- =============================================

-- ---------------------------------------------
-- 누적 수정계수: 이벤트 보유 종목만 펼친다 (전 종목 대비 약 1/200 크기).
-- 효력일(effective_date) 이전 거래일에 그 이후의 모든 이벤트 계수를 곱한다. EXP(SUM(LN(x))) = 곱.
-- 효력일이 KST 오늘 이후인 이벤트(예정 권리락)는 제외한다. DAILY 가 매일 REFRESH 하므로 효력일 당일 자동 반영된다.
-- ---------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stock_adjust_factor AS
SELECT p.ticker,
       p.trade_date,
       COALESCE(EXP(SUM(LN(x.price_factor))), 1.0)::double precision  AS cum_price_factor,
       COALESCE(EXP(SUM(LN(x.volume_factor))), 1.0)::double precision AS cum_volume_factor
FROM tb_stock_daily_price p
         JOIN (SELECT DISTINCT ticker FROM tb_stock_adjust_event
               WHERE effective_date <= (now() AT TIME ZONE 'Asia/Seoul')::date) t ON t.ticker = p.ticker
         LEFT JOIN tb_stock_adjust_event x ON x.ticker = p.ticker
                                           AND x.effective_date > p.trade_date
                                           AND x.effective_date <= (now() AT TIME ZONE 'Asia/Seoul')::date
GROUP BY p.ticker, p.trade_date
WITH DATA;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY 에 유니크 인덱스가 필요하다
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_stock_adjust_factor ON mv_stock_adjust_factor (ticker, trade_date);

-- ---------------------------------------------
-- 수정주가 뷰: 가격 소비자가 쓰는 유일한 진입점. 계수가 없는 종목·일자는 원주가 그대로다.
-- ---------------------------------------------
CREATE OR REPLACE VIEW vw_stock_daily_price_adj AS
SELECT p.ticker,
       p.trade_date,
       (p.open_price * COALESCE(f.cum_price_factor, 1))::double precision   AS adj_open,
       (p.high_price * COALESCE(f.cum_price_factor, 1))::double precision   AS adj_high,
       (p.low_price * COALESCE(f.cum_price_factor, 1))::double precision    AS adj_low,
       (p.close_price * COALESCE(f.cum_price_factor, 1))::double precision  AS adj_close,
       (p.volume * COALESCE(f.cum_volume_factor, 1))::double precision      AS adj_volume,
       p.trading_value,
       p.close_price                                                        AS raw_close,
       p.volume                                                             AS raw_volume,
       p.change_rate
FROM tb_stock_daily_price p
         LEFT JOIN mv_stock_adjust_factor f ON f.ticker = p.ticker AND f.trade_date = p.trade_date;

-- ---------------------------------------------
-- 영업일 캘린더 정본: KOSPI 종합(0001) 지수 일봉이 존재하는 날짜 집합. 미래 판정은 tb_stock_market_holiday 를 쓴다.
-- ---------------------------------------------
CREATE OR REPLACE VIEW vw_stock_market_calendar AS
SELECT trade_date
FROM tb_stock_index_daily
WHERE index_code = '0001';

-- =============================================
-- 지표 계층 (선순환의 출발점). 모두 vw_stock_daily_price_adj(수정주가) 위에서 계산하며 DOUBLE PRECISION 이다. 종목 일별 지표는 테이블(위 참조).
-- 갱신 순서: mv_stock_adjust_factor → tb_stock_daily_metric(증분 재계산) → mv_stock_index_metric → mv_stock_sector_daily
-- (DerivedMetricRefreshService.REFRESH_ORDER). 갱신이 3분을 넘으면 증분 테이블로 전환하되 뷰 이름은 유지한다.
-- =============================================

-- ---------------------------------------------
-- 종목 일별 지표는 2026-09-08 부터 테이블 tb_stock_daily_metric (db/stock-schema.sql) 이다. 전체 재계산이 25분이라 MV 로는
-- DAILY 락(40분) 안에 못 끝나, DerivedViewRefresher.recomputeDailyMetric 이 최근 N일만 창 함수로 다시 계산해 upsert 한다.
-- 옛 mv_stock_daily_metric 은 stock-derived-rebuild.sql 이 지운다.
-- ---------------------------------------------

-- ---------------------------------------------
-- 지수 지표: RS = 종목 수익률 − 지수 수익률(동일 창)
-- ---------------------------------------------
-- 이동평균은 누적합 − LAG 로 계산한다(2026-09-09): double precision 의 AVG 는 역전이 함수가 없어 ROWS n PRECEDING 프레임을
-- 행마다 다시 훑는다(131만 행 REFRESH 150초). 누적합은 행당 덧셈 1회이고 파티션 앞부분은 LEAST(rn, n) 으로 프레임이 잘린 AVG 와 같다.
-- 계산식 설명은 DerivedViewRefresher.DAILY_METRIC_UPSERT_SQL 주석 참고. 정의를 바꿨으므로 운영은 stock-derived-rebuild.sql 로 재생성한다.
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stock_index_metric AS
SELECT index_code,
       trade_date,
       close_value,
       close_value / NULLIF(LAG(close_value, 1) OVER w, 0) - 1                       AS ret_1d,
       close_value / NULLIF(LAG(close_value, 5) OVER w, 0) - 1                       AS ret_5d,
       close_value / NULLIF(LAG(close_value, 20) OVER w, 0) - 1                      AS ret_20d,
       close_value / NULLIF(LAG(close_value, 60) OVER w, 0) - 1                      AS ret_60d,
       close_value / NULLIF(LAG(close_value, 120) OVER w, 0) - 1                     AS ret_120d,
       (cum_close - COALESCE(LAG(cum_close, 20) OVER w, 0)) / LEAST(rn, 20)          AS ma_20,
       (cum_close - COALESCE(LAG(cum_close, 60) OVER w, 0)) / LEAST(rn, 60)          AS ma_60,
       (cum_close - COALESCE(LAG(cum_close, 120) OVER w, 0)) / LEAST(rn, 120)        AS ma_120
FROM (
    SELECT index_code,
           trade_date,
           close_price::double precision                                              AS close_value,
           ROW_NUMBER() OVER (c ROWS UNBOUNDED PRECEDING)                             AS rn,
           SUM(close_price::double precision) OVER (c ROWS UNBOUNDED PRECEDING)       AS cum_close
    FROM tb_stock_index_daily
    WINDOW c AS (PARTITION BY index_code ORDER BY trade_date)
) cum
WINDOW w AS (PARTITION BY index_code ORDER BY trade_date)
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_stock_index_metric ON mv_stock_index_metric (index_code, trade_date);

-- ---------------------------------------------
-- 섹터 일별 집계 (KRX 중분류 현재 매핑 기준). 매핑 이력은 구축일부터라 과거 구성은 현재 구성으로 근사한다(문서화된 편향).
-- near_high_ratio: 52주 고점의 95% 이상에 있는 종목 비율.
-- ---------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stock_sector_daily AS
SELECT s.sector_code,
       p.trade_date,
       COUNT(*)                                                                                   AS member_count,
       SUM(v.market_cap)                                                                          AS sum_market_cap,
       SUM(p.trading_value)                                                                       AS sum_trading_value,
       AVG(p.change_rate)::double precision                                                       AS avg_change_rate,
       (SUM(p.change_rate * v.market_cap) / NULLIF(SUM(v.market_cap), 0))::double precision       AS cap_weighted_change_rate,
       AVG(CASE WHEN p.change_rate > 0 THEN 1.0 ELSE 0.0 END)::double precision                   AS rising_ratio,
       AVG(CASE WHEN m.dist_high_52w >= -0.05 THEN 1.0 ELSE 0.0 END)::double precision            AS near_high_ratio,
       SUM(i.foreign_net_amt)                                                                     AS foreign_net_sum,
       SUM(i.institution_net_amt)                                                                 AS institution_net_sum
FROM tb_stock_daily_price p
         JOIN tb_stock_sector_map s ON s.ticker = p.ticker AND s.source = 'KRX' AND s.valid_to IS NULL
         LEFT JOIN tb_stock_valuation_daily v ON v.ticker = p.ticker AND v.trade_date = p.trade_date
         LEFT JOIN tb_stock_investor_daily i ON i.ticker = p.ticker AND i.trade_date = p.trade_date
         LEFT JOIN tb_stock_daily_metric m ON m.ticker = p.ticker AND m.trade_date = p.trade_date
GROUP BY s.sector_code, p.trade_date
WITH DATA;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_stock_sector_daily ON mv_stock_sector_daily (sector_code, trade_date);

-- ---------------------------------------------
-- 유니버스: 활성·주권·비거래정지·비관리·비정리매매 + 시총 1,000억·거래대금 5일 평균 10억 하한 (1차 상수, 추후 테이블화).
-- 시총은 스냅샷이 있는 날만 판정한다(과거 백필 구간은 시총 이력이 없어 거래대금 하한만 적용).
-- ---------------------------------------------
CREATE OR REPLACE VIEW vw_stock_universe_daily AS
SELECT p.trade_date, p.ticker
FROM tb_stock_daily_price p
         JOIN tb_stock_master m ON m.ticker = p.ticker
         LEFT JOIN tb_stock_valuation_daily v ON v.ticker = p.ticker AND v.trade_date = p.trade_date
         LEFT JOIN tb_stock_daily_metric d ON d.ticker = p.ticker AND d.trade_date = p.trade_date
WHERE m.security_group = 'ST'
  AND m.is_active = TRUE
  AND m.is_suspended = FALSE
  AND m.is_administrative = FALSE
  AND m.is_liquidating = FALSE
  AND (v.market_cap IS NULL OR v.market_cap >= 100000000000)
  AND COALESCE(d.tv_avg_5d, p.trading_value) >= 1000000000;
-- <<< END db/stock-derived.sql

-- >>> BEGIN db/stock-seed.sql
-- =============================================
-- 주식 수집 모듈 시드 데이터 (psql 수기 적용, 재실행 안전)
-- =============================================
-- 국내 섹터 ↔ 미국 참조 지표 매핑 (스펙 §4.4). sector_code 는 CUSTOM 섹터 코드다 —
-- KRX 지수업종 중분류와 1:1 이 아니므로 tb_stock_sector_map 에 source='CUSTOM' 행을 붙여 쓴다.
-- 심볼은 kis.overseas.symbols 와 같아야 tb_stock_global_market_daily 와 조인된다. 미제공 지표(WTI·10년물·LNG)는 제외.
-- =============================================
INSERT INTO tb_stock_global_sector_map (sector_code, global_symbol, weight) VALUES
    ('SEMICON', 'COMP', 1.0), ('SEMICON', 'SOX', 1.0), ('SEMICON', 'SMH', 1.0), ('SEMICON', 'SOXX', 1.0),
    ('SEMICON', 'NVDA', 1.0), ('SEMICON', 'AMD', 1.0), ('SEMICON', 'MU', 1.0), ('SEMICON', 'TSM', 1.0), ('SEMICON', 'ASML', 1.0),
    ('AI_DC', 'COMP', 1.0), ('AI_DC', 'NVDA', 1.0), ('AI_DC', 'MSFT', 1.0), ('AI_DC', 'GOOGL', 1.0), ('AI_DC', 'AMZN', 1.0),
    ('BATTERY', 'TSLA', 1.0), ('BATTERY', 'ALB', 1.0), ('BATTERY', 'LIT', 1.0),
    ('BIO', 'XBI', 1.0), ('BIO', 'IBB', 1.0),
    ('DEFENSE', 'ITA', 1.0), ('DEFENSE', 'LMT', 1.0), ('DEFENSE', 'RTX', 1.0), ('DEFENSE', 'NOC', 1.0),
    ('FINANCE', 'XLF', 1.0), ('FINANCE', 'KRE', 1.0),
    ('ENERGY_CHEM', 'XLE', 1.0),
    ('INTERNET', 'COMP', 1.0),
    ('ROBOT', 'BOTZ', 1.0), ('ROBOT', 'ROBO', 1.0), ('ROBOT', 'TSLA', 1.0), ('ROBOT', 'NVDA', 1.0),
    ('MARKET', '.DJI', 1.0), ('MARKET', 'SPX', 1.0), ('MARKET', 'COMP', 1.0), ('MARKET', 'FX@KRW', 1.0)
ON CONFLICT (sector_code, global_symbol) DO NOTHING;
-- <<< END db/stock-seed.sql

-- >>> BEGIN db/advisor-schema.sql
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
COMMENT ON COLUMN tb_advisor_run.status            IS '상태: RUNNING | SUCCESS | PARTIAL | FAILED | SKIPPED';
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
    predicted    VARCHAR(10)      NOT NULL,
    p_up         DOUBLE PRECISION          DEFAULT NULL,
    base_value   DOUBLE PRECISION          DEFAULT NULL,
    exit_value   DOUBLE PRECISION          DEFAULT NULL,
    actual_ret   DOUBLE PRECISION          DEFAULT NULL,
    bench_ret    DOUBLE PRECISION          DEFAULT NULL,
    band         DOUBLE PRECISION          DEFAULT NULL,
    actual_dir   VARCHAR(10)               DEFAULT NULL,
    hit          BOOLEAN                   DEFAULT NULL,
    brier        DOUBLE PRECISION          DEFAULT NULL,
    scored_at    TIMESTAMPTZ(6)   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_advisor_call_score PRIMARY KEY (advice_id, subject_type, subject_code, horizon_days)
);

COMMENT ON TABLE  tb_advisor_call_score              IS '국면(지수 방향)·주도 섹터 콜 채점';
COMMENT ON COLUMN tb_advisor_call_score.advice_id    IS '판단 식별자';
COMMENT ON COLUMN tb_advisor_call_score.subject_type IS 'INDEX (0001/1001 방향) | SECTOR (주도 섹터)';
COMMENT ON COLUMN tb_advisor_call_score.subject_code IS '지수 코드 또는 섹터 코드';
COMMENT ON COLUMN tb_advisor_call_score.horizon_days IS '호라이즌 (5)';
COMMENT ON COLUMN tb_advisor_call_score.stage        IS 'PROVISIONAL | CONFIRMED';
COMMENT ON COLUMN tb_advisor_call_score.status       IS 'SCORED | MISSING';
COMMENT ON COLUMN tb_advisor_call_score.predicted    IS 'INDEX: UP|NEUTRAL|DOWN, SECTOR: LEAD (주도 예측)';
COMMENT ON COLUMN tb_advisor_call_score.p_up         IS 'INDEX 예측 신뢰도';
COMMENT ON COLUMN tb_advisor_call_score.base_value   IS '기준일 종가 (close-to-close)';
COMMENT ON COLUMN tb_advisor_call_score.exit_value   IS '청산일 종가';
COMMENT ON COLUMN tb_advisor_call_score.actual_ret   IS '실현 수익률 (지수 또는 섹터 지수)';
COMMENT ON COLUMN tb_advisor_call_score.bench_ret    IS 'SECTOR: 같은 구간 시장 지수 수익률 (INDEX 는 NULL)';
COMMENT ON COLUMN tb_advisor_call_score.band         IS 'INDEX: NEUTRAL 판정 밴드 = band-sigma × σ_5d';
COMMENT ON COLUMN tb_advisor_call_score.actual_dir   IS 'INDEX: |ret| < band 면 NEUTRAL, 아니면 부호';
COMMENT ON COLUMN tb_advisor_call_score.hit          IS 'INDEX: predicted == actual_dir, SECTOR: actual_ret − bench_ret > 0';
COMMENT ON COLUMN tb_advisor_call_score.brier        IS 'INDEX: (p_up − 1[ret>0])² (부호 기준, 밴드 무관)';
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
-- <<< END db/advisor-schema.sql

-- >>> BEGIN db/advisor-seed.sql
-- =============================================
-- AI 시장 판단(advisor) 시드 데이터 (psql 수기 적용, 재실행 안전)
-- =============================================
-- 초기 가중치 세트(SEED). 설계 사전값이며 IC 사전 추정(IC_BACKFILL) 세트가 활성화되면 대체된다.
-- 코드는 SignalCode enum 상수명과 같아야 한다. GLOBAL_LINK 는 국면 특징으로만 쓰고 종목 점수에는 넣지 않는다(base 0, enabled false).
-- 밸류(VALUE_RANK)는 당일 스냅샷만 있어 과거 IC 를 못 구하므로 multiplier 1.0 고정 대상이다.
-- =============================================
INSERT INTO tb_advisor_weight_set (as_of, window_days, n_eff, source, is_active, reason)
SELECT CURRENT_DATE, 0, 0, 'SEED', TRUE, '설계 사전 가중치 (2026-09-13). IC 사전 추정 전 초기값'
WHERE NOT EXISTS (SELECT 1 FROM tb_advisor_weight_set WHERE source = 'SEED');

INSERT INTO tb_advisor_signal_weight (weight_set_id, signal_code, base_weight, multiplier, weight, enabled, note)
SELECT s.weight_set_id, v.code, v.base, 1.0, v.base, v.base > 0, v.note
FROM tb_advisor_weight_set s,
     (VALUES ('MOM_20D',         0.12, '20일 모멘텀'),
             ('MOM_60D',         0.10, '60일 모멘텀'),
             ('TREND_MA',        0.12, 'MA 정배열 강도'),
             ('NEAR_HIGH_52W',   0.10, '52주 고점 근접'),
             ('TV_SURGE',        0.10, '거래대금 5/60 급증'),
             ('FOREIGN_FLOW',    0.12, '외국인 5일 순매수/시총'),
             ('INST_FLOW',       0.08, '기관 5일 순매수/시총'),
             ('SECTOR_STRENGTH', 0.10, '섹터 5일 시총가중 등락'),
             ('RS_INDEX',        0.08, '지수 대비 20일 상대강도'),
             ('VALUE_RANK',      0.05, 'PBR 낮을수록 (당일 스냅샷, IC 갱신 대상 아님)'),
             ('VOL_20D',         0.03, '20일 변동성 낮을수록'),
             ('GLOBAL_LINK',     0.00, '해외 연동 — 국면 특징 전용, 종목 점수 제외')) AS v(code, base, note)
WHERE s.source = 'SEED'
ON CONFLICT (weight_set_id, signal_code) DO NOTHING;
-- <<< END db/advisor-seed.sql

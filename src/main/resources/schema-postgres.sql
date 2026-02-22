-- =============================================
-- PostgreSQL 18 스키마 (MariaDB schema.sql 변환)
-- =============================================
-- 변환 규칙:
--   bigint auto_increment → BIGINT GENERATED ALWAYS AS IDENTITY
--   bit                   → BOOLEAN
--   longtext              → TEXT
--   datetime(6)           → TIMESTAMP(6)
--   int                   → INTEGER
--   decimal               → NUMERIC
--   camelCase 컬럼명      → snake_case (PG unquoted 식별자 소문자 폴딩 + JPA PhysicalNamingStrategy 대응)
--   comment '...' (인라인)→ COMMENT ON ... (별도 문장, 각 테이블 생성 직후 배치)
-- =============================================

-- =============================================
-- DROP (FK 역순, CASCADE로 의존 객체 포함 제거)
-- =============================================

DROP TABLE IF EXISTS tb_memo CASCADE;
DROP TABLE IF EXISTS tb_memo_category CASCADE;
DROP TABLE IF EXISTS tb_jira_worklog CASCADE;
DROP TABLE IF EXISTS tb_jira_issue CASCADE;
DROP TABLE IF EXISTS tb_common_code CASCADE;
DROP TABLE IF EXISTS tb_common_class CASCADE;
DROP TABLE IF EXISTS tb_user_authority_map CASCADE;
DROP TABLE IF EXISTS tb_user CASCADE;
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
    category_id   VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    created_by    VARCHAR(255) NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
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
COMMENT ON COLUMN tb_post.category_id   IS '카테고리 ID (FK)';
COMMENT ON COLUMN tb_post.created_at    IS '생성일시';
COMMENT ON COLUMN tb_post.created_by    IS '생성자';
COMMENT ON COLUMN tb_post.updated_at    IS '수정일시';
COMMENT ON COLUMN tb_post.updated_by    IS '수정자';

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
    created_at  TIMESTAMP(6) NOT NULL,
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
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(255) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
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
    created_at TIMESTAMP(6) NOT NULL,
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
-- 공통코드 관리 테이블들 (엔티티 기준 Surrogate Key 패턴)
-- ---------------------------------------------

CREATE TABLE tb_common_class
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NULL,
    description     VARCHAR(512) NULL,
    attribute1_name VARCHAR(64)  NULL,
    attribute2_name VARCHAR(64)  NULL,
    attribute3_name VARCHAR(64)  NULL,
    attribute4_name VARCHAR(64)  NULL,
    attribute5_name VARCHAR(64)  NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP(6) NOT NULL,
    created_by      VARCHAR(32)  NULL,
    updated_at      TIMESTAMP(6) NULL,
    updated_by      VARCHAR(32)  NULL,
    CONSTRAINT uk_common_class_code UNIQUE (code)
);
COMMENT ON TABLE  tb_common_class                  IS '공통코드 클래스 (코드 그룹 정의)';
COMMENT ON COLUMN tb_common_class.id               IS '클래스 내부 ID (PK, Surrogate Key)';
COMMENT ON COLUMN tb_common_class.code             IS '클래스 코드 (Natural Key) 예: REGION_CLASS';
COMMENT ON COLUMN tb_common_class.name             IS '클래스명 예: "지역분류"';
COMMENT ON COLUMN tb_common_class.description      IS '설명';
COMMENT ON COLUMN tb_common_class.attribute1_name  IS '동적속성1 이름';
COMMENT ON COLUMN tb_common_class.attribute2_name  IS '동적속성2 이름';
COMMENT ON COLUMN tb_common_class.attribute3_name  IS '동적속성3 이름';
COMMENT ON COLUMN tb_common_class.attribute4_name  IS '동적속성4 이름';
COMMENT ON COLUMN tb_common_class.attribute5_name  IS '동적속성5 이름';
COMMENT ON COLUMN tb_common_class.is_active        IS '활성화 여부';
COMMENT ON COLUMN tb_common_class.created_at       IS '생성일시';
COMMENT ON COLUMN tb_common_class.created_by       IS '생성자';
COMMENT ON COLUMN tb_common_class.updated_at       IS '수정일시';
COMMENT ON COLUMN tb_common_class.updated_by       IS '수정자';

-- ---------------------------------------------

CREATE TABLE tb_common_code
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(32)  NOT NULL,
    name             VARCHAR(64)  NOT NULL,
    description      VARCHAR(512) NULL,
    attribute1_value VARCHAR(128) NULL,
    attribute2_value VARCHAR(128) NULL,
    attribute3_value VARCHAR(128) NULL,
    attribute4_value VARCHAR(128) NULL,
    attribute5_value VARCHAR(128) NULL,
    sort             INTEGER      NOT NULL DEFAULT 0,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP(6) NOT NULL,
    created_by       VARCHAR(32)  NULL,
    updated_at       TIMESTAMP(6) NULL,
    updated_by       VARCHAR(32)  NULL,
    class_id         BIGINT       NOT NULL,
    child_class_id   BIGINT       NULL,
    CONSTRAINT uk_common_code_class_code     UNIQUE (class_id, code),
    CONSTRAINT fk_common_code_class_id       FOREIGN KEY (class_id)       REFERENCES tb_common_class (id),
    CONSTRAINT fk_common_code_child_class_id FOREIGN KEY (child_class_id) REFERENCES tb_common_class (id)
);
COMMENT ON TABLE  tb_common_code                  IS '공통코드 (실제 코드값 저장)';
COMMENT ON COLUMN tb_common_code.id               IS '코드 내부 ID (PK, Surrogate Key)';
COMMENT ON COLUMN tb_common_code.code             IS '코드값 (Natural Key)';
COMMENT ON COLUMN tb_common_code.name             IS '코드명';
COMMENT ON COLUMN tb_common_code.description      IS '설명';
COMMENT ON COLUMN tb_common_code.attribute1_value IS '동적속성1 값';
COMMENT ON COLUMN tb_common_code.attribute2_value IS '동적속성2 값';
COMMENT ON COLUMN tb_common_code.attribute3_value IS '동적속성3 값';
COMMENT ON COLUMN tb_common_code.attribute4_value IS '동적속성4 값';
COMMENT ON COLUMN tb_common_code.attribute5_value IS '동적속성5 값';
COMMENT ON COLUMN tb_common_code.sort             IS '정렬순서';
COMMENT ON COLUMN tb_common_code.is_active        IS '활성화 여부';
COMMENT ON COLUMN tb_common_code.created_at       IS '생성일시';
COMMENT ON COLUMN tb_common_code.created_by       IS '생성자';
COMMENT ON COLUMN tb_common_code.updated_at       IS '수정일시';
COMMENT ON COLUMN tb_common_code.updated_by       IS '수정자';
COMMENT ON COLUMN tb_common_code.class_id         IS '소속 클래스 ID (FK)';
COMMENT ON COLUMN tb_common_code.child_class_id   IS '하위 클래스 ID (NULL이면 leaf 노드)';

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
    created_at    TIMESTAMP(6) NOT NULL,
    created_by    VARCHAR(32)  NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
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
    started    TIMESTAMP(6) NOT NULL,
    worklog_id VARCHAR(256) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(32)  NULL,
    updated_at TIMESTAMP(6) NOT NULL,
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
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(255) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
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
    created_at  TIMESTAMP(6) NOT NULL,
    created_by  VARCHAR(255) NULL,
    updated_at  TIMESTAMP(6) NOT NULL,
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
    created_at       TIMESTAMP(6)  NOT NULL,
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
    created_at       TIMESTAMP(6)  NOT NULL,
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
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
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

-- 한글, 영문대소문자 검색을 위한 gin_bigm_ops 적용
-- LOWER(col) LIKE LOWER(?) 패턴으로 검색
CREATE INDEX idx_post_subject_bigm ON tb_post USING GIN (LOWER(subject) gin_bigm_ops);
CREATE INDEX idx_post_normal_body_bigm ON tb_post USING GIN (LOWER(normal_body) gin_bigm_ops);

CREATE INDEX idx_category_name_bigm ON tb_category USING GIN (LOWER(name) gin_bigm_ops);

CREATE INDEX idx_tag_name_bigm ON tb_tag USING GIN (LOWER(name) gin_bigm_ops);

CREATE INDEX idx_memo_content_bigm ON tb_memo USING GIN (LOWER(content) gin_bigm_ops);
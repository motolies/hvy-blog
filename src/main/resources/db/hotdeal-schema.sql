-- =============================================
-- 핫딜 스케줄러 테이블 DDL
-- PostgreSQL
-- =============================================

-- 핫딜 사이트 설정 테이블
CREATE TABLE IF NOT EXISTS tb_hot_deal_site
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_code          VARCHAR(32)   NOT NULL,
    site_name          VARCHAR(128)  NOT NULL,
    site_url           VARCHAR(512)  NOT NULL,
    board_url          VARCHAR(512)  NOT NULL,
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    requires_login     BOOLEAN       NOT NULL DEFAULT FALSE,
    login_id           VARCHAR(128)           DEFAULT NULL,
    login_password     VARCHAR(256)           DEFAULT NULL,
    min_recommendation INTEGER       NOT NULL DEFAULT 10,
    min_view_count     INTEGER       NOT NULL DEFAULT 1000,
    min_comment_count  INTEGER       NOT NULL DEFAULT 25,
    created_at         TIMESTAMP(6)  NOT NULL,
    created_by         VARCHAR(255)           DEFAULT NULL,
    updated_at         TIMESTAMP(6)  NOT NULL,
    updated_by         VARCHAR(255)           DEFAULT NULL,
    CONSTRAINT uk_hot_deal_site_code UNIQUE (site_code)
);

COMMENT ON TABLE  tb_hot_deal_site                    IS '핫딜 사이트 설정';
COMMENT ON COLUMN tb_hot_deal_site.id                 IS '사이트 ID';
COMMENT ON COLUMN tb_hot_deal_site.site_code          IS '사이트 코드 (PPOMPPU, CLIEN 등)';
COMMENT ON COLUMN tb_hot_deal_site.site_name          IS '사이트명';
COMMENT ON COLUMN tb_hot_deal_site.site_url           IS '사이트 기본 URL';
COMMENT ON COLUMN tb_hot_deal_site.board_url          IS '핫딜 게시판 URL 경로';
COMMENT ON COLUMN tb_hot_deal_site.enabled            IS '활성화 여부';
COMMENT ON COLUMN tb_hot_deal_site.requires_login     IS '로그인 필요 여부';
COMMENT ON COLUMN tb_hot_deal_site.login_id           IS '로그인 ID (로그인 필요 사이트)';
COMMENT ON COLUMN tb_hot_deal_site.login_password     IS '로그인 비밀번호 (로그인 필요 사이트)';
COMMENT ON COLUMN tb_hot_deal_site.min_recommendation IS 'Slack 알림 최소 추천수';
COMMENT ON COLUMN tb_hot_deal_site.min_view_count     IS 'Slack 알림 최소 조회수';
COMMENT ON COLUMN tb_hot_deal_site.min_comment_count  IS 'Slack 알림 최소 댓글수';
COMMENT ON COLUMN tb_hot_deal_site.created_at         IS '생성일시';
COMMENT ON COLUMN tb_hot_deal_site.created_by         IS '생성자';
COMMENT ON COLUMN tb_hot_deal_site.updated_at         IS '수정일시';
COMMENT ON COLUMN tb_hot_deal_site.updated_by         IS '수정자';


-- 핫딜 아이템 테이블
CREATE TABLE IF NOT EXISTS tb_hot_deal_item
(
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id              BIGINT        NOT NULL,
    external_id          VARCHAR(64)   NOT NULL,
    title                VARCHAR(512)  NOT NULL,
    url                  VARCHAR(1024) NOT NULL,
    author               VARCHAR(64)            DEFAULT NULL,
    recommendation_count   INTEGER       NOT NULL DEFAULT 0,
    unrecommendation_count INTEGER       NOT NULL DEFAULT 0,
    view_count             INTEGER       NOT NULL DEFAULT 0,
    comment_count        INTEGER       NOT NULL DEFAULT 0,
    price                VARCHAR(128)           DEFAULT NULL,
    deal_category        VARCHAR(64)            DEFAULT NULL,
    thumbnail_url        VARCHAR(1024)         DEFAULT NULL,
    notified             BOOLEAN       NOT NULL DEFAULT FALSE,
    notified_at          TIMESTAMP(6)           DEFAULT NULL,
    scraped_at           TIMESTAMP(6)  NOT NULL,
    created_at           TIMESTAMP(6)  NOT NULL,
    created_by           VARCHAR(255)           DEFAULT NULL,
    updated_at           TIMESTAMP(6)  NOT NULL,
    updated_by           VARCHAR(255)           DEFAULT NULL,
    CONSTRAINT fk_hot_deal_item_site_id
        FOREIGN KEY (site_id) REFERENCES tb_hot_deal_site (id),
    CONSTRAINT uk_hot_deal_item_site_external
        UNIQUE (site_id, external_id)
);

COMMENT ON TABLE  tb_hot_deal_item                        IS '핫딜 아이템';
COMMENT ON COLUMN tb_hot_deal_item.id                     IS '아이템 ID';
COMMENT ON COLUMN tb_hot_deal_item.site_id                IS '사이트 ID (FK)';
COMMENT ON COLUMN tb_hot_deal_item.external_id            IS '원본 게시글 ID';
COMMENT ON COLUMN tb_hot_deal_item.title                  IS '게시글 제목';
COMMENT ON COLUMN tb_hot_deal_item.url                    IS '원본 게시글 URL';
COMMENT ON COLUMN tb_hot_deal_item.author                 IS '작성자';
COMMENT ON COLUMN tb_hot_deal_item.recommendation_count   IS '추천수';
COMMENT ON COLUMN tb_hot_deal_item.unrecommendation_count IS '비추천수';
COMMENT ON COLUMN tb_hot_deal_item.view_count             IS '조회수';
COMMENT ON COLUMN tb_hot_deal_item.comment_count          IS '댓글수';
COMMENT ON COLUMN tb_hot_deal_item.price                  IS '가격 (사이트별 포맷 상이)';
COMMENT ON COLUMN tb_hot_deal_item.deal_category          IS '딜 카테고리';
COMMENT ON COLUMN tb_hot_deal_item.thumbnail_url IS '썸네일 이미지 URL';
COMMENT ON COLUMN tb_hot_deal_item.notified               IS 'Slack 알림 전송 여부';
COMMENT ON COLUMN tb_hot_deal_item.notified_at            IS 'Slack 알림 전송 일시';
COMMENT ON COLUMN tb_hot_deal_item.scraped_at             IS '스크래핑 일시';
COMMENT ON COLUMN tb_hot_deal_item.created_at             IS '생성일시';
COMMENT ON COLUMN tb_hot_deal_item.created_by             IS '생성자';
COMMENT ON COLUMN tb_hot_deal_item.updated_at             IS '수정일시';
COMMENT ON COLUMN tb_hot_deal_item.updated_by             IS '수정자';

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_hot_deal_item_site_notified
    ON tb_hot_deal_item (site_id, notified);
CREATE INDEX IF NOT EXISTS idx_hot_deal_item_scraped_at
    ON tb_hot_deal_item (scraped_at DESC);


-- =============================================
-- 기존 DB 마이그레이션: 비추천수 및 댓글 필터 추가
-- =============================================
ALTER TABLE tb_hot_deal_item
    ADD COLUMN IF NOT EXISTS unrecommendation_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE tb_hot_deal_site
    ADD COLUMN IF NOT EXISTS min_comment_count INTEGER NOT NULL DEFAULT 25;

COMMENT ON COLUMN tb_hot_deal_item.unrecommendation_count IS '비추천수';
COMMENT ON COLUMN tb_hot_deal_site.min_comment_count      IS 'Slack 알림 최소 댓글수';


-- =============================================
-- 초기 데이터: 뽐뿌 게시판
-- =============================================
INSERT INTO tb_hot_deal_site
    (site_code, site_name, site_url, board_url,
     enabled, requires_login,
     min_recommendation, min_view_count, min_comment_count,
     created_at, created_by, updated_at, updated_by)
VALUES
    ('PPOMPPU', '뽐뿌', 'https://www.ppomppu.co.kr', '/zboard/zboard.php?id=ppomppu',
     TRUE, FALSE,
     20, 4000, 25,
     NOW(), 'SYSTEM', NOW(), 'SYSTEM')
ON CONFLICT (site_code) DO NOTHING;

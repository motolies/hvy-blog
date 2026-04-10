-- 시리즈(연재물) 테이블 생성

CREATE TABLE IF NOT EXISTS tb_series
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(256) NOT NULL,
    description VARCHAR(1024) NULL,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255) NULL,
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
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

CREATE TABLE IF NOT EXISTS tb_series_post
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

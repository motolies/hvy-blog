-- 발행 포스트의 임시저장 초안 테이블
CREATE TABLE tb_post_draft (
    post_id     BIGINT PRIMARY KEY REFERENCES tb_post(id) ON DELETE CASCADE,
    subject     VARCHAR(512) NOT NULL,
    body        TEXT         NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL,
    updated_by  VARCHAR(255) NULL
);
COMMENT ON TABLE  tb_post_draft            IS '발행 포스트의 임시저장 초안';
COMMENT ON COLUMN tb_post_draft.post_id    IS '포스트 ID (FK, PK)';
COMMENT ON COLUMN tb_post_draft.subject    IS '초안 제목';
COMMENT ON COLUMN tb_post_draft.body       IS '초안 본문';
COMMENT ON COLUMN tb_post_draft.updated_at IS '수정일시';
COMMENT ON COLUMN tb_post_draft.updated_by IS '수정자';

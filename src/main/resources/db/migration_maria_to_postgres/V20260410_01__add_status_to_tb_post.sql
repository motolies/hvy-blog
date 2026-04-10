-- tb_post 테이블에 status 컬럼 추가 (게시 상태: TEM=임시저장, PUB=배포완료)
-- 기존 데이터는 모두 PUB(배포완료)로 설정

ALTER TABLE tb_post ADD COLUMN IF NOT EXISTS status VARCHAR(3) NOT NULL DEFAULT 'PUB';

COMMENT ON COLUMN tb_post.status IS '게시 상태 (TEM=임시저장, PUB=배포완료)';

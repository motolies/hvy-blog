-- ============================================================
-- V20260828_01__admin_dashboard.sql
-- 관리자 대시보드 지원 인덱스
--
-- ⚠️ 이 프로젝트에는 Flyway 가 없다(ddl-auto: validate, sql.init.mode: never).
--    파일명은 관례일 뿐 자동 실행되지 않는다 — psql 로 수기 실행하고,
--    같은 내용을 schema-postgres.sql 의 INDEX 섹션에도 반드시 반영할 것.
--    한쪽만 하면 신규 환경에서 조용히 느려진다.
--
-- ⚠️ CREATE INDEX CONCURRENTLY 는 트랜잭션 블록 안에서 실행할 수 없다.
--    psql 에서 한 문장씩 개별 실행할 것 (\i 로 파일 통째 실행 금지).
-- ============================================================

-- [1] 조회 beacon 전용 부분 인덱스 — 트래픽·인기글 위젯의 핵심
--     기존 정규식 `request_uri ~ '^/api/post/[0-9]+$'` 는 btree 가 서빙할 수 없어
--     60일치 전체 로그를 훑으며 매 행에 정규식을 돌렸다. 그 접근을 폐기하고
--     상수 술어 3개로 고정된 부분 인덱스로 대체한다.
--
--     ★ 아래 3개 조건을 쿼리 WHERE 절에 "문자열 그대로" 포함해야
--       플래너의 predicate implication prover 가 이 인덱스를 선택한다.
--       한 글자라도 바뀌면 즉시 seq scan 으로 퇴화하며, 아무 경고도 나지 않는다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_system_log_post_view
    ON tb_system_log (created_at)
 WHERE status = 'SUCC'
   AND http_method_type = 'POST'
   AND request_uri LIKE '/api/post/%/view';

-- [2] 이상 징후 위젯 — 최근 에러 N건
--     FAIL 은 전체 로그 대비 극소수라 인덱스 스캔 후 LIMIT 에서 즉시 종료된다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_system_log_status_created
    ON tb_system_log (status, created_at DESC);

-- [3] 핫딜 사이트별 마지막 수집 시각
--     기존 idx_hot_deal_item_site_notified (site_id, notified) 로는 MAX(scraped_at) 를 서빙할 수 없다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_hot_deal_item_site_scraped
    ON tb_hot_deal_item (site_id, scraped_at DESC);

-- ------------------------------------------------------------
-- [선택] 전체 기간 인기글 — 포스트가 수천 건을 넘어가면 추가
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_view_count
--     ON tb_post (view_count DESC) WHERE status = 'PUB' AND public_access = true;

-- ------------------------------------------------------------
-- [Phase 3] 발행 일시 — 현재 글 작성 흐름은 "새 글" 버튼에서 빈 글을 만들고
--   나중에 채우는 구조라 created_at 이 발행일이 아니다.
--   ⚠️ ddl-auto: validate 이므로 반드시 이 DDL 을 먼저 적용한 뒤 엔티티 필드를 추가할 것.
--      순서를 뒤집으면 애플리케이션이 기동에 실패한다.
-- ALTER TABLE tb_post ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ(6) NULL;
-- COMMENT ON COLUMN tb_post.published_at IS '최초 발행 일시 (TEM→PUB 전환 시점, NULL 이면 created_at 사용)';
-- UPDATE tb_post SET published_at = created_at WHERE status = 'PUB' AND published_at IS NULL;

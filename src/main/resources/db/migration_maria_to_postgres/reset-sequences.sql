-- =============================================
-- PostgreSQL IDENTITY 시퀀스 리셋 스크립트
-- =============================================
-- 마이그레이션 후 반드시 실행하세요.
-- GENERATED ALWAYS AS IDENTITY 컬럼의 시퀀스를
-- max(id) + 1로 초기화하여 새 레코드 삽입 시
-- 기존 ID와 충돌하지 않도록 합니다.
--
-- 실행 방법:
--   psql -U {user} -d {dbname} -f reset-sequences.sql
-- =============================================

-- tb_authority
SELECT setval(
    pg_get_serial_sequence('tb_authority', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_authority), 0) + 1,
    false
);

-- tb_post
SELECT setval(
    pg_get_serial_sequence('tb_post', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_post), 0) + 1,
    false
);

-- tb_search_engine
SELECT setval(
    pg_get_serial_sequence('tb_search_engine', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_search_engine), 0) + 1,
    false
);

-- tb_tag
SELECT setval(
    pg_get_serial_sequence('tb_tag', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_tag), 0) + 1,
    false
);

-- tb_user
SELECT setval(
    pg_get_serial_sequence('tb_user', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_user), 0) + 1,
    false
);

-- tb_common_class
SELECT setval(
    pg_get_serial_sequence('tb_common_class', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_common_class), 0) + 1,
    false
);

-- tb_common_code
SELECT setval(
    pg_get_serial_sequence('tb_common_code', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_common_code), 0) + 1,
    false
);

-- tb_jira_issue
SELECT setval(
    pg_get_serial_sequence('tb_jira_issue', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_jira_issue), 0) + 1,
    false
);

-- tb_jira_worklog
SELECT setval(
    pg_get_serial_sequence('tb_jira_worklog', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_jira_worklog), 0) + 1,
    false
);

-- tb_memo_category
SELECT setval(
    pg_get_serial_sequence('tb_memo_category', 'id'),
    COALESCE((SELECT MAX(id) FROM tb_memo_category), 0) + 1,
    false
);

-- 결과 확인
SELECT
    schemaname,
    sequencename,
    last_value
FROM pg_sequences
WHERE schemaname = 'blog'
  AND sequencename LIKE 'tb_%'
ORDER BY sequencename;

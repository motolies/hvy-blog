-- =============================================
-- 마이그레이션 검증 스크립트
-- =============================================
-- MariaDB와 PostgreSQL 양쪽에서 각각 실행하여
-- 결과를 비교하세요.
--
-- MariaDB에서 실행:
--   mysql -u {user} -p {dbname} < verify-migration.sql
--
-- PostgreSQL에서 실행:
--   psql -U {user} -d {dbname} -f verify-migration.sql
--
-- 컬럼명 차이로 인해 각각 동일한 로직이지만
-- 컬럼명이 다릅니다. 아래 쿼리는 PostgreSQL 기준입니다.
-- =============================================

-- =============================================
-- 테이블별 ROW COUNT 확인
-- =============================================
SELECT
    'tb_authority'       AS table_name, COUNT(*) AS row_count FROM tb_authority
UNION ALL SELECT
    'tb_user',                           COUNT(*) FROM tb_user
UNION ALL SELECT
    'tb_user_authority_map',             COUNT(*) FROM tb_user_authority_map
UNION ALL SELECT
    'tb_category',                       COUNT(*) FROM tb_category
UNION ALL SELECT
    'tb_post',                           COUNT(*) FROM tb_post
UNION ALL SELECT
    'tb_file',                           COUNT(*) FROM tb_file
UNION ALL SELECT
    'tb_tag',                            COUNT(*) FROM tb_tag
UNION ALL SELECT
    'tb_post_tag_map',                   COUNT(*) FROM tb_post_tag_map
UNION ALL SELECT
    'tb_search_engine',                  COUNT(*) FROM tb_search_engine
UNION ALL SELECT
    'tb_common_class',                   COUNT(*) FROM tb_common_class
UNION ALL SELECT
    'tb_common_code',                    COUNT(*) FROM tb_common_code
UNION ALL SELECT
    'tb_jira_issue',                     COUNT(*) FROM tb_jira_issue
UNION ALL SELECT
    'tb_jira_worklog',                   COUNT(*) FROM tb_jira_worklog
UNION ALL SELECT
    'tb_memo_category',                  COUNT(*) FROM tb_memo_category
UNION ALL SELECT
    'tb_memo',                           COUNT(*) FROM tb_memo
ORDER BY table_name;

-- =============================================
-- FK 정합성 확인 (PostgreSQL에서만 실행)
-- =============================================

-- tb_post의 category_id 정합성
SELECT 'tb_post → tb_category FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_post p
WHERE NOT EXISTS (SELECT 1 FROM tb_category c WHERE c.id = p.category_id);

-- tb_file의 post_id 정합성
SELECT 'tb_file → tb_post FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_file f
WHERE NOT EXISTS (SELECT 1 FROM tb_post p WHERE p.id = f.post_id);

-- tb_post_tag_map 정합성
SELECT 'tb_post_tag_map → tb_post FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_post_tag_map m
WHERE NOT EXISTS (SELECT 1 FROM tb_post p WHERE p.id = m.post_id);

SELECT 'tb_post_tag_map → tb_tag FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_post_tag_map m
WHERE NOT EXISTS (SELECT 1 FROM tb_tag t WHERE t.id = m.tag_id);

-- tb_common_code의 class_id 정합성
SELECT 'tb_common_code → tb_common_class (class_id) FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_common_code cc
WHERE NOT EXISTS (SELECT 1 FROM tb_common_class cls WHERE cls.id = cc.class_id);

-- tb_jira_worklog의 issue_id 정합성
SELECT 'tb_jira_worklog → tb_jira_issue FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_jira_worklog w
WHERE NOT EXISTS (SELECT 1 FROM tb_jira_issue i WHERE i.id = w.issue_id);

-- tb_memo의 category_id 정합성
SELECT 'tb_memo → tb_memo_category FK 위반 수' AS check_name,
       COUNT(*) AS violation_count
FROM tb_memo m
WHERE m.category_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tb_memo_category mc WHERE mc.id = m.category_id);

-- =============================================
-- 샘플 데이터 확인
-- =============================================

-- 최신 포스트 5개
SELECT id, subject, public_access, view_count, created_at
FROM tb_post
ORDER BY created_at DESC
LIMIT 5;

-- common_class/code 구조 변환 확인
SELECT cls.id, cls.code, cls.name,
       COUNT(cc.id) AS code_count
FROM tb_common_class cls
LEFT JOIN tb_common_code cc ON cc.class_id = cls.id
GROUP BY cls.id, cls.code, cls.name
ORDER BY cls.code;

-- 시퀀스 현재 값 확인 (마이그레이션 후 max(id)보다 커야 함)
SELECT
    sequencename,
    last_value
FROM pg_sequences
WHERE schemaname = 'blog'
  AND sequencename LIKE 'tb_%'
ORDER BY sequencename;

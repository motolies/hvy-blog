-- =============================================
-- MariaDB → PostgreSQL 데이터 추출 스크립트
-- =============================================
-- 실행 방법:
--   mysql -N -r -u {user} -p {dbname} < extract-from-mariadb.sql > extracted-data.sql
--
-- 이 스크립트는 MariaDB 데이터를 PostgreSQL INSERT 문으로 변환하여 출력합니다.
-- 출력된 파일을 PostgreSQL에서 실행하면 데이터가 이관됩니다.
-- =============================================

-- 구분자 없이 출력하기 위한 설정
SET group_concat_max_len = 1073741824;

-- =============================================
-- 출력 헤더
-- =============================================
SELECT '-- =============================================';
SELECT '-- PostgreSQL 데이터 적재 스크립트';
SELECT CONCAT('-- 추출 일시: ', NOW());
SELECT '-- =============================================';
SELECT '';
SELECT 'BEGIN;';
SELECT '';
SELECT '-- 기존 데이터 초기화 (자식 → 부모 순서, CASCADE로 FK 처리)';
SELECT 'TRUNCATE tb_memo CASCADE;';
SELECT 'TRUNCATE tb_memo_category CASCADE;';
SELECT 'TRUNCATE tb_search_engine CASCADE;';
SELECT 'TRUNCATE tb_common_code CASCADE;';
SELECT 'TRUNCATE tb_common_class CASCADE;';
SELECT 'TRUNCATE tb_post_tag_map CASCADE;';
SELECT 'TRUNCATE tb_file CASCADE;';
SELECT 'TRUNCATE tb_post CASCADE;';
SELECT 'TRUNCATE tb_tag CASCADE;';
SELECT 'TRUNCATE tb_category CASCADE;';
SELECT 'TRUNCATE tb_user_authority_map CASCADE;';
SELECT 'TRUNCATE tb_user CASCADE;';
SELECT 'TRUNCATE tb_authority CASCADE;';
SELECT '';
SELECT '-- IDENTITY 컬럼에 직접 값을 삽입하기 위해 세션 수준 설정';
SELECT '-- (OVERRIDING SYSTEM VALUE를 각 INSERT에 사용)';
SELECT '';

-- =============================================
-- 1. tb_authority
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 1. tb_authority';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_authority (id, name) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CASE WHEN name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(name, '''', ''''''), '''') END,
    ');'
)
FROM tb_authority
ORDER BY id;

SELECT '';

-- =============================================
-- 2. tb_user
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 2. tb_user';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_user (id, name, login_id, password, is_enabled) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CASE WHEN name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(name, '''', ''''''), '''') END, ', ',
    CASE WHEN loginId IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(loginId, '''', ''''''), '''') END, ', ',
    CASE WHEN password IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(password, '''', ''''''), '''') END, ', ',
    CASE WHEN isEnabled IS NULL THEN 'NULL' WHEN isEnabled = 1 THEN 'true' ELSE 'false' END,
    ');'
)
FROM tb_user
ORDER BY id;

SELECT '';

-- =============================================
-- 3. tb_user_authority_map
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 3. tb_user_authority_map';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_user_authority_map (user_id, authority_id) VALUES (',
    userId, ', ',
    authorityId,
    ');'
)
FROM tb_user_authority_map
ORDER BY userId, authorityId;

SELECT '';

-- =============================================
-- 4. tb_category (재귀 CTE로 depth 기반 정렬 - 부모 먼저, 자식 나중)
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 4. tb_category';
SELECT '-- ---------------------------------------------';

-- 재귀 CTE로 depth를 계산하여 부모 → 자식 순서 보장 (자기참조 FK 위반 방지)
WITH RECURSIVE category_tree AS (
    SELECT id, name, seq, fullName, fullPath, parentId, 0 AS depth
    FROM tb_category
    WHERE parentId IS NULL
    UNION ALL
    SELECT c.id, c.name, c.seq, c.fullName, c.fullPath, c.parentId, ct.depth + 1
    FROM tb_category c
    INNER JOIN category_tree ct ON c.parentId = ct.id
)
SELECT CONCAT(
    'INSERT INTO tb_category (id, name, seq, full_name, full_path, parent_id) VALUES (',
    CONCAT('''', REPLACE(id, '''', ''''''), ''''), ', ',
    CONCAT('''', REPLACE(name, '''', ''''''), ''''), ', ',
    seq, ', ',
    CONCAT('''', REPLACE(fullName, '''', ''''''), ''''), ', ',
    CONCAT('''', REPLACE(fullPath, '''', ''''''), ''''), ', ',
    CASE WHEN parentId IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(parentId, '''', ''''''), '''') END,
    ');'
)
FROM category_tree
ORDER BY depth, id;

SELECT '';

-- =============================================
-- 5. tb_post
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 5. tb_post';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_post (id, subject, body, normal_body, public_access, main_page, view_count, category_id, created_at, created_by, updated_at, updated_by) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CONCAT('''', REPLACE(subject, '''', ''''''), ''''), ', ',
    CONCAT('$body$', body, '$body$'), ', ',
    CONCAT('$body$', normalBody, '$body$'), ', ',
    CASE WHEN publicAccess = 1 THEN 'true' ELSE 'false' END, ', ',
    CASE WHEN mainPage = 1 THEN 'true' ELSE 'false' END, ', ',
    viewCount, ', ',
    CONCAT('''', REPLACE(categoryId, '''', ''''''), ''''), ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
    CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_post
ORDER BY id;

SELECT '';

-- =============================================
-- 6. tb_file
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 6. tb_file';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_file (id, post_id, origin_name, type, path, file_size, deleted, created_at, created_by) VALUES (',
    id, ', ',
    postId, ', ',
    CONCAT('''', REPLACE(originName, '''', ''''''), ''''), ', ',
    CONCAT('''', REPLACE(type, '''', ''''''), ''''), ', ',
    CONCAT('''', REPLACE(path, '''', ''''''), ''''), ', ',
    fileSize, ', ',
    CASE WHEN deleted = 1 THEN 'true' ELSE 'false' END, ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_file
ORDER BY id;

SELECT '';

-- =============================================
-- 7. tb_tag
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 7. tb_tag';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_tag (id, name, created_at, created_by) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CONCAT('''', REPLACE(name, '''', ''''''), ''''), ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_tag
ORDER BY id;

SELECT '';

-- =============================================
-- 8. tb_post_tag_map
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 8. tb_post_tag_map';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_post_tag_map (post_id, tag_id) VALUES (',
    postId, ', ',
    tagId,
    ');'
)
FROM tb_post_tag_map
ORDER BY postId, tagId;

SELECT '';

-- =============================================
-- 9. tb_search_engine
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 9. tb_search_engine';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_search_engine (id, name, url, seq, created_at, created_by, updated_at, updated_by) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CONCAT('''', REPLACE(name, '''', ''''''), ''''), ', ',
    CONCAT('''', REPLACE(url, '''', ''''''), ''''), ', ',
    seq, ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
    CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_search_engine
ORDER BY id;

SELECT '';

-- =============================================
-- 10. tb_common_class
-- =============================================
-- MariaDB 실제 스키마: id(PK) + code(UNIQUE) + name (이미 surrogate key 적용됨)
-- 단순 컬럼명 매핑: camelCase → snake_case, bit → boolean
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 10. tb_common_class';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_common_class (id, code, name, description, attribute1_name, attribute2_name, attribute3_name, attribute4_name, attribute5_name, is_active, created_at, created_by, updated_at, updated_by) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CONCAT('''', REPLACE(code, '''', ''''''), ''''), ', ',
    CASE WHEN name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(name, '''', ''''''), '''') END, ', ',
    CASE WHEN description IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(description, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute1Name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute1Name, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute2Name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute2Name, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute3Name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute3Name, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute4Name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute4Name, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute5Name IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute5Name, '''', ''''''), '''') END, ', ',
    CASE WHEN isActive = 1 THEN 'true' ELSE 'false' END, ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
    CASE WHEN updatedAt IS NULL THEN 'NULL' ELSE CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), '''') END, ', ',
    CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_common_class
ORDER BY id;

SELECT '';

-- =============================================
-- 11. tb_common_code
-- =============================================
-- MariaDB 실제 스키마: id(PK) + classId(BIGINT FK) + childClassId(BIGINT FK)
--   (이미 surrogate key 적용됨, childClassCode는 PostgreSQL에 없으므로 제외)
-- 단순 컬럼명 매핑: camelCase → snake_case, bit → boolean
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 11. tb_common_code';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_common_code (id, code, name, description, attribute1_value, attribute2_value, attribute3_value, attribute4_value, attribute5_value, sort, is_active, created_at, created_by, updated_at, updated_by, class_id, child_class_id) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CONCAT('''', REPLACE(code, '''', ''''''), ''''), ', ',
    CONCAT('''', REPLACE(name, '''', ''''''), ''''), ', ',
    CASE WHEN description IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(description, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute1Value IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute1Value, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute2Value IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute2Value, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute3Value IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute3Value, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute4Value IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute4Value, '''', ''''''), '''') END, ', ',
    CASE WHEN attribute5Value IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(attribute5Value, '''', ''''''), '''') END, ', ',
    sort, ', ',
    CASE WHEN isActive = 1 THEN 'true' ELSE 'false' END, ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
    CASE WHEN updatedAt IS NULL THEN 'NULL' ELSE CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), '''') END, ', ',
    CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END, ', ',
    classId, ', ',
    CASE WHEN childClassId IS NULL THEN 'NULL' ELSE childClassId END,
    ');'
)
FROM tb_common_code
ORDER BY classId, sort, code;

SELECT '';

-- =============================================
-- 12. tb_jira_issue
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 12. tb_jira_issue';
SELECT '-- ---------------------------------------------';
--
-- SELECT CONCAT(
--     'INSERT INTO tb_jira_issue (id, jira_issue_id, issue_key, issue_link, summary, issue_type, status, assignee, components, story_points, start_date, end_date, sprint, created_at, created_by, updated_at, updated_by) OVERRIDING SYSTEM VALUE VALUES (',
--     id, ', ',
--     JiraIssueId, ', ',
--     CONCAT('''', REPLACE(issueKey, '''', ''''''), ''''), ', ',
--     CONCAT('''', REPLACE(issueLink, '''', ''''''), ''''), ', ',
--     CONCAT('''', REPLACE(summary, '''', ''''''), ''''), ', ',
--     CASE WHEN issueType IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(issueType, '''', ''''''), '''') END, ', ',
--     CASE WHEN status IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(status, '''', ''''''), '''') END, ', ',
--     CASE WHEN assignee IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(assignee, '''', ''''''), '''') END, ', ',
--     CASE WHEN components IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(components, '''', ''''''), '''') END, ', ',
--     CASE WHEN storyPoints IS NULL THEN 'NULL' ELSE storyPoints END, ', ',
--     CASE WHEN startDate IS NULL THEN 'NULL' ELSE CONCAT('''', startDate, '''') END, ', ',
--     CASE WHEN endDate IS NULL THEN 'NULL' ELSE CONCAT('''', endDate, '''') END, ', ',
--     CASE WHEN sprint IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(sprint, '''', ''''''), '''') END, ', ',
--     CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
--     CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
--     CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
--     CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
--     ');'
-- )
-- FROM tb_jira_issue
-- ORDER BY id;
--
-- SELECT '';

-- =============================================
-- 13. tb_jira_worklog
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 13. tb_jira_worklog';
SELECT '-- ---------------------------------------------';
--
-- SELECT CONCAT(
--     'INSERT INTO tb_jira_worklog (id, issue_id, issue_key, issue_type, status, issue_link, summary, author, components, time_spent, time_hours, comment, started, worklog_id, created_at, created_by, updated_at, updated_by) OVERRIDING SYSTEM VALUE VALUES (',
--     id, ', ',
--     issueId, ', ',
--     CONCAT('''', REPLACE(issueKey, '''', ''''''), ''''), ', ',
--     CASE WHEN issueType IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(issueType, '''', ''''''), '''') END, ', ',
--     CASE WHEN status IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(status, '''', ''''''), '''') END, ', ',
--     CONCAT('''', REPLACE(issueLink, '''', ''''''), ''''), ', ',
--     CONCAT('''', REPLACE(summary, '''', ''''''), ''''), ', ',
--     CONCAT('''', REPLACE(author, '''', ''''''), ''''), ', ',
--     CASE WHEN components IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(components, '''', ''''''), '''') END, ', ',
--     CONCAT('''', REPLACE(timeSpent, '''', ''''''), ''''), ', ',
--     timeHours, ', ',
--     CASE WHEN comment IS NULL THEN 'NULL' ELSE CONCAT('$body$', comment, '$body$') END, ', ',
--     CONCAT('''', DATE_FORMAT(started, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
--     CONCAT('''', REPLACE(worklogId, '''', ''''''), ''''), ', ',
--     CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
--     CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
--     CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
--     CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
--     ');'
-- )
-- FROM tb_jira_worklog
-- ORDER BY id;
--
-- SELECT '';

-- =============================================
-- 14. tb_memo_category
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 14. tb_memo_category';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_memo_category (id, name, seq, created_at, created_by, updated_at, updated_by) OVERRIDING SYSTEM VALUE VALUES (',
    id, ', ',
    CONCAT('''', REPLACE(name, '''', ''''''), ''''), ', ',
    seq, ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
    CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_memo_category
ORDER BY id;

SELECT '';

-- =============================================
-- 15. tb_memo
-- =============================================
SELECT '-- ---------------------------------------------';
SELECT '-- 15. tb_memo';
SELECT '-- ---------------------------------------------';

SELECT CONCAT(
    'INSERT INTO tb_memo (id, content, category_id, deleted, created_at, created_by, updated_at, updated_by) VALUES (',
    id, ', ',
    CONCAT('$body$', content, '$body$'), ', ',
    CASE WHEN categoryId IS NULL THEN 'NULL' ELSE categoryId END, ', ',
    CASE WHEN deleted = 1 THEN 'true' ELSE 'false' END, ', ',
    CONCAT('''', DATE_FORMAT(createdAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN createdBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(createdBy, '''', ''''''), '''') END, ', ',
    CONCAT('''', DATE_FORMAT(updatedAt, '%Y-%m-%d %H:%i:%s.%f'), ''''), ', ',
    CASE WHEN updatedBy IS NULL THEN 'NULL' ELSE CONCAT('''', REPLACE(updatedBy, '''', ''''''), '''') END,
    ');'
)
FROM tb_memo
WHERE deleted = 0
ORDER BY id;

SELECT '';

-- =============================================
-- 완료
-- =============================================
SELECT 'COMMIT;';
SELECT '';
SELECT '-- 마이그레이션 데이터 추출 완료';

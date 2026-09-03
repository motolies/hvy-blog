-- ============================================================
-- V20260830_01__platform_links.sql
-- PLATFORM 루트코드 시드 + FAVORITE 스키마 icon 확장
-- (관리자 전용 홈 "플랫폼" 섹션 / 통합 관리 화면 /admin/favorites)
--
-- ⚠️ 이 프로젝트에는 Flyway 가 없다(ddl-auto: validate, sql.init.mode: never).
--    파일명은 관례일 뿐 자동 실행되지 않는다 — psql 로 수기 실행할 것.
--    DDL 변경이 없으므로 schema-postgres.sql 은 수정 대상이 아니다
--    (attributes/attribute_schema JSONB 와 idx_mc_attributes GIN 이 이미 존재).
--    같은 내용을 data.sql 에도 반영했다(신규 환경용) — 한쪽만 하면 로컬과 운영이 갈라진다.
--
-- ⚠️ 실행 후 반드시 캐시를 비울 것:
--      curl -X DELETE -H "Authorization: Bearer <ADMIN_JWT>" https://<host>/api/codes/admin/cache
--    psql 직접 INSERT 는 애플리케이션의 L1(Caffeine)/L2(Redis) 를 모르므로
--    masterCodeTree:"full" 과 root:FAVORITE 이 낡은 채 남는다.
--
-- ⚠️ hvy.master-code.public-roots (env: MASTER_CODE_PUBLIC_ROOTS) 는 절대 건드리지 말 것.
--    PLATFORM 이 그 목록에 들어가는 순간 /api/codes/tree/PLATFORM 이 비인증에게 열린다.
--    운영 값은 'FAVORITE' 하나로 유지한다.
--
-- 재실행 안전(멱등): 이미 존재하면 아무것도 하지 않는다.
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- [1] PLATFORM 루트
--     url/icon 의 sensitive:"true" 는 이중 방어다 — 화이트리스트가 오염되는 사고가 나도
--     MasterCodeAttributeSanitizer 가 내부 호스트명(url)만은 비관리자 응답에서 걷어낸다.
-- ------------------------------------------------------------
INSERT INTO tb_master_code (parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
SELECT NULL, 0, NULL, 'PLATFORM', '플랫폼', '내부 운영 플랫폼 링크 (관리자 전용)',
       '[{"key":"url","label":"URL","type":"text","sensitive":"true"},{"key":"icon","label":"아이콘","type":"text","sensitive":"true"}]'::JSONB,
       6, true, NOW(), 'SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM tb_master_code WHERE code = 'PLATFORM' AND parent_id IS NULL);

-- ------------------------------------------------------------
-- [2] PLATFORM 그룹 (depth=1)
--     링크(depth=2)는 시드하지 않는다 — 실제 내부 호스트명은 git 에 남기지 않고
--     /admin/favorites 플랫폼 탭에서 직접 입력한다.
-- ------------------------------------------------------------
INSERT INTO tb_master_code (parent_id, depth, code, name, attributes, sort, is_active, created_at, created_by)
SELECT r.id, 1, v.code, v.name, v.attrs::JSONB, v.sort, true, NOW(), 'SYSTEM'
  FROM tb_master_code r
 CROSS JOIN (VALUES
       ('DEPLOY',        '배포',   '{"icon":"Rocket"}',   1),
       ('OBSERVABILITY', '관측',   '{"icon":"Activity"}', 2),
       ('DATA',          '데이터', '{"icon":"Database"}', 3)
      ) AS v(code, name, attrs, sort)
 WHERE r.code = 'PLATFORM' AND r.parent_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM tb_master_code c WHERE c.parent_id = r.id AND c.code = v.code);

-- ------------------------------------------------------------
-- [3] FAVORITE 루트 스키마에 icon 추가
--     통합 관리 화면이 두 탭에서 같은 폼(이름·URL·아이콘)을 쓰기 위해 필요하다.
--     ⚠️ sensitive 를 붙이지 않는다. FAVORITE 의 url 은 공개되어야 한다 (PLATFORM 과 정반대).
--        붙이면 sanitizer 가 비관리자 응답에서 url 을 걷어내고, favoriteService.extractLinks 의
--        `|| '#'` 폴백 때문에 에러 없이 공개 홈 링크가 전부 '#' 로 죽는다.
-- ------------------------------------------------------------
UPDATE tb_master_code
   SET attribute_schema = '[{"key":"url","label":"URL","type":"text"},{"key":"icon","label":"아이콘","type":"text"}]'::JSONB,
       updated_at = NOW(),
       updated_by = 'SYSTEM'
 WHERE code = 'FAVORITE' AND parent_id IS NULL
   AND NOT attribute_schema @> '[{"key":"icon"}]'::JSONB;

-- ------------------------------------------------------------
-- [4] path 갱신 — PLATFORM 서브트리로만 한정한다.
--     (운영 테이블 전체를 다시 쓰지 않기 위해. data.sql 의 전역 UPDATE 와 다른 점이다.)
-- ------------------------------------------------------------
UPDATE tb_master_code
   SET path = '/' || id::TEXT
 WHERE code = 'PLATFORM' AND parent_id IS NULL
   AND path IS DISTINCT FROM '/' || id::TEXT;

UPDATE tb_master_code c
   SET path = p.path || '/' || c.id::TEXT
  FROM tb_master_code p
 WHERE p.id = c.parent_id
   AND p.code = 'PLATFORM' AND p.parent_id IS NULL
   AND c.path IS DISTINCT FROM p.path || '/' || c.id::TEXT;

UPDATE tb_master_code c
   SET path = p.path || '/' || c.id::TEXT
  FROM tb_master_code p
  JOIN tb_master_code r ON r.id = p.parent_id
 WHERE p.id = c.parent_id
   AND r.code = 'PLATFORM' AND r.parent_id IS NULL
   AND c.path IS DISTINCT FROM p.path || '/' || c.id::TEXT;

COMMIT;

-- ============================================================
-- 검증
-- ============================================================
-- (1) PLATFORM 서브트리 확인
-- SELECT id, parent_id, depth, path, code, name, attributes, sort
--   FROM tb_master_code
--  WHERE path LIKE (SELECT path FROM tb_master_code WHERE code = 'PLATFORM' AND parent_id IS NULL) || '%'
--  ORDER BY depth, sort;
--
-- (2) 두 루트의 스키마 대조 — PLATFORM 만 sensitive 가 있어야 한다
-- SELECT code, attribute_schema FROM tb_master_code
--  WHERE code IN ('FAVORITE', 'PLATFORM') AND parent_id IS NULL;
--
-- (3) 캐시 evict 후, 비인증 공개 조회가 각각 어떻게 나오는지 확인
--   curl -s <host>/api/codes/tree/PLATFORM   # data: []           ← 반드시 빈 배열
--   curl -s <host>/api/codes/tree/FAVORITE   # url 이 살아 있어야 함 ← '#' 회귀 검사

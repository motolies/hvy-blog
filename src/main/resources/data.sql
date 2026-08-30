-- =============================================
-- 기초 데이터 (PostgreSQL)
-- =============================================
-- 변환 규칙:
--   camelCase 컬럼명 → snake_case
--   Boolean: 1/0 → true/false
--   blog. 스키마 접두사 제거
--   IDENTITY 컬럼 직접 삽입: OVERRIDING SYSTEM VALUE
--   MasterCode: surrogate key 패턴 (code 기반 subquery)
-- =============================================

-- ---------------------------------------------
-- 사용자 및 권한
-- ---------------------------------------------

INSERT INTO tb_user (id, login_id, password, name, is_enabled)
OVERRIDING SYSTEM VALUE
SELECT 1,
       'admin',
       '$2a$10$4Yx00Kexb3IShV37fapj..5AgIHCtLa2yLokdo0SBdTTAo/MTs0I6', -- bye
       'motolies',
       true
WHERE NOT EXISTS (SELECT 1
                  FROM tb_user
                  WHERE login_id = 'admin');

INSERT INTO tb_authority (id, name)
OVERRIDING SYSTEM VALUE
SELECT 1, 'ROLE_ADMIN'
WHERE NOT EXISTS (SELECT 1
                  FROM tb_authority
                  WHERE name = 'ROLE_ADMIN');

INSERT INTO tb_authority (id, name)
OVERRIDING SYSTEM VALUE
SELECT 2, 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1
                  FROM tb_authority
                  WHERE name = 'ROLE_USER');

INSERT INTO tb_user_authority_map (user_id, authority_id)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1
                  FROM tb_user_authority_map
                  WHERE user_id = 1
                    AND authority_id = 1);

INSERT INTO tb_user_authority_map (user_id, authority_id)
SELECT 1, 2
WHERE NOT EXISTS (SELECT 1
                  FROM tb_user_authority_map
                  WHERE user_id = 1
                    AND authority_id = 2);

-- ---------------------------------------------
-- 카테고리
-- ---------------------------------------------

INSERT INTO tb_category (seq, id, name, full_name, full_path, parent_id)
VALUES (0, 'ROOT', '전체글', '/전체글/', '/ROOT/', null);

-- ---------------------------------------------
-- 검색 엔진
-- ---------------------------------------------

INSERT INTO tb_search_engine (name, url, seq, created_at, updated_at)
VALUES ('Naver', 'http://search.naver.com/search.naver?sm=tab_hty.top&where=nexearch&ie=utf8&query=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Daum', 'http://search.daum.net/search?w=tot&DA=YZR&t__nil_searchbox=btn&sug=&sugo=&q=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Google', 'https://www.google.com/search?gl=US&num=100&newwindow=1&tbs=&q=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Google Image', 'https://www.google.com/search?gl=US&biw=1920&bih=955&tbm=isch&sa=1&btnG=%EA%B2%80%EC%83%89&q=%s&oq=&gs_l=', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Google Cache', 'http://webcache.googleusercontent.com/search?q=cache:%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Wiki', 'https://ko.wikipedia.org/w/index.php?search=%s&title=%ED%8A%B9%EC%88%98%3A%EA%B2%80%EC%83%89&go=%EB%B3%B4%EA%B8%B0', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('EMS', 'http://service.epost.go.kr/trace.RetrieveEmsRigiTraceList.comm?POST_CODE=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Naver 영어사전', 'http://endic.naver.com/search.nhn?sLn=kr&isOnlyViewEE=N&query=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('아마존 직배송', 'https://track.shiptrack.co.kr/epost/%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('알리익스프레스 스댄다드 조회', 'http://ex.actcore.com/inboundOcean/Tracing.wo?method=tracingGuest&statustype=HT&country=KO&refno=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('네이버 중고나라', 'https://cafe.naver.com/ca-fe/home/search/c-articles?ss=ON_SALE&pt=DIRECT&dt=MEET&wp=1w&q=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24'),
       ('Google Translate', 'https://translate.google.com/?sl=en&tl=ko&text=%s&op=translate', 0, '2022-05-30 08:20:26', '2022-05-30 08:20:26');

-- =============================================
-- 마스터코드 데이터 (자기참조 트리 구조)
-- =============================================
-- 구조: 루트 노드(depth=0) → 하위 노드(depth=1+)
-- 기존 CLASS → 루트 노드, CODE → 하위 노드로 변환
-- attribute1~5 → JSONB attributes로 변환
-- =============================================

-- ---------------------------------------------
-- 1. REGION (지역분류) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES ('0000000000001', NULL, 0, '/0000000000001', 'REGION', '지역분류', '대한민국 지역 분류 코드',
        '[{"key":"latitude","label":"위도","type":"text"},{"key":"longitude","label":"경도","type":"text"},{"key":"population","label":"인구수","type":"text"},{"key":"area","label":"면적","type":"text"},{"key":"zipCode","label":"우편번호","type":"text"}]'::JSONB,
        1, true, NOW(), 'SYSTEM');

-- 지역 코드 (depth=1)
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000007', (SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL) || '/0000000000007', 'SEOUL',   '서울특별시', '대한민국의 수도',    '{"latitude":"37.5665","longitude":"126.9780","population":"9720846","area":"605.21","zipCode":"04500"}'::JSONB, 1, true, NOW(), 'SYSTEM'),
('0000000000008', (SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL) || '/0000000000008', 'BUSAN',   '부산광역시', '대한민국의 제2도시',  '{"latitude":"35.1796","longitude":"129.0756","population":"3448737","area":"769.82","zipCode":"48058"}'::JSONB, 2, true, NOW(), 'SYSTEM'),
('0000000000009', (SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL) || '/0000000000009', 'INCHEON', '인천광역시', '대한민국의 관문도시', '{"latitude":"37.4563","longitude":"126.7052","population":"2947217","area":"1065.4","zipCode":"21554"}'::JSONB, 3, true, NOW(), 'SYSTEM'),
('0000000000010', (SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL) || '/0000000000010', 'DAEGU',   '대구광역시', '대한민국의 섬유도시', '{"latitude":"35.8714","longitude":"128.6014","population":"2410700","area":"883.56","zipCode":"41911"}'::JSONB, 4, true, NOW(), 'SYSTEM');

-- 서울 구 코드 (depth=2)
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000011', (SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1) || '/0000000000011', 'GANGNAM', '강남구', '서울의 강남 지역', '{"latitude":"37.5172","longitude":"127.0473","population":"569901","area":"39.50","zipCode":"06028"}'::JSONB, 1, true, NOW(), 'SYSTEM'),
('0000000000012', (SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1) || '/0000000000012', 'SEOCHO',  '서초구', '서울의 서초 지역', '{"latitude":"37.4837","longitude":"127.0324","population":"433453","area":"47.00","zipCode":"06593"}'::JSONB, 2, true, NOW(), 'SYSTEM'),
('0000000000013', (SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1) || '/0000000000013', 'SONGPA',  '송파구', '서울의 송파 지역', '{"latitude":"37.5145","longitude":"127.1059","population":"686489","area":"33.88","zipCode":"05505"}'::JSONB, 3, true, NOW(), 'SYSTEM'),
('0000000000014', (SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1) || '/0000000000014', 'JONGNO',  '종로구', '서울의 중심 지역', '{"latitude":"37.5735","longitude":"126.9788","population":"162820","area":"23.91","zipCode":"03045"}'::JSONB, 4, true, NOW(), 'SYSTEM');

-- 부산 구 코드 (depth=2)
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000015', (SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1) || '/0000000000015', 'HAEUNDAE', '해운대구', '부산의 관광 중심지', '{"latitude":"35.1631","longitude":"129.1640","population":"411349","area":"51.44","zipCode":"48059"}'::JSONB, 1, true, NOW(), 'SYSTEM'),
('0000000000016', (SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1) || '/0000000000016', 'SAHA',     '사하구',   '부산의 서쪽 지역',   '{"latitude":"35.1041","longitude":"128.9743","population":"334957","area":"40.89","zipCode":"49424"}'::JSONB, 2, true, NOW(), 'SYSTEM'),
('0000000000017', (SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1) || '/0000000000017', 'BUSANJIN', '부산진구', '부산의 중심 지역',   '{"latitude":"35.1630","longitude":"129.0531","population":"384593","area":"29.70","zipCode":"47176"}'::JSONB, 3, true, NOW(), 'SYSTEM'),
('0000000000018', (SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1) || '/0000000000018', 'DONGNAE',  '동래구',   '부산의 전통 온천지', '{"latitude":"35.2046","longitude":"129.0840","population":"270748","area":"16.63","zipCode":"47809"}'::JSONB, 4, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 2. STATUS (상태분류) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, sort, is_active, created_at, created_by)
VALUES ('0000000000002', NULL, 0, '/0000000000002', 'STATUS', '상태분류', '일반적인 상태 코드', 2, true, NOW(), 'SYSTEM');

INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, sort, is_active, created_at, created_by)
VALUES
('0000000000019', (SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL) || '/0000000000019', 'ACTIVE',    '활성',   '활성화된 상태',   1, true, NOW(), 'SYSTEM'),
('0000000000020', (SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL) || '/0000000000020', 'INACTIVE',  '비활성', '비활성화된 상태', 2, true, NOW(), 'SYSTEM'),
('0000000000021', (SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL) || '/0000000000021', 'PENDING',   '대기',   '처리 대기 상태', 3, true, NOW(), 'SYSTEM'),
('0000000000022', (SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL) || '/0000000000022', 'COMPLETED', '완료',   '처리 완료 상태', 4, true, NOW(), 'SYSTEM'),
('0000000000023', (SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL) || '/0000000000023', 'CANCELLED', '취소',   '취소된 상태',    5, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 3. CATEGORY (카테고리분류) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES ('0000000000003', NULL, 0, '/0000000000003', 'CATEGORY', '카테고리분류', '일반 카테고리 분류',
        '[{"key":"color","label":"색상코드","type":"text"},{"key":"icon","label":"아이콘","type":"text"},{"key":"order","label":"순서","type":"text"}]'::JSONB,
        3, true, NOW(), 'SYSTEM');

INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000024', (SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL) || '/0000000000024', 'TECH',      '기술',         'IT 및 기술 관련',  '{"color":"#007bff","icon":"fas fa-laptop-code","order":"1"}'::JSONB,    1, true, NOW(), 'SYSTEM'),
('0000000000025', (SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL) || '/0000000000025', 'BUSINESS',  '비즈니스',     '비즈니스 및 경영', '{"color":"#28a745","icon":"fas fa-briefcase","order":"2"}'::JSONB,      2, true, NOW(), 'SYSTEM'),
('0000000000026', (SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL) || '/0000000000026', 'DESIGN',    '디자인',       '디자인 및 창작',   '{"color":"#dc3545","icon":"fas fa-palette","order":"3"}'::JSONB,        3, true, NOW(), 'SYSTEM'),
('0000000000027', (SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL) || '/0000000000027', 'EDUCATION', '교육',         '교육 및 학습',     '{"color":"#ffc107","icon":"fas fa-graduation-cap","order":"4"}'::JSONB, 4, true, NOW(), 'SYSTEM'),
('0000000000028', (SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL) || '/0000000000028', 'LIFESTYLE', '라이프스타일', '일상 및 취미',     '{"color":"#6f42c1","icon":"fas fa-heart","order":"5"}'::JSONB,          5, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 4. JIRA_STATUS (지라 상태 코드) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES ('0000000000004', NULL, 0, '/0000000000004', 'JIRA_STATUS', '지라 상태 코드', '지라 상태 코드',
        '[{"key":"statusCategory","label":"상태카테고리","type":"text"},{"key":"isDone","label":"완료여부","type":"text"}]'::JSONB,
        4, true, '2025-08-24 04:01:40.000000', 'SYSTEM');

INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000029', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000029', '해야 할 일(작업요청)', '해야 할 일(작업요청)', '해야 할 일(작업요청)', '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       1,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000030', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000030', '작업예정(요청확인)',   '작업예정(요청확인)',   '작업예정(요청확인)',   '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       2,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000031', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000031', '중지(HOLDING)',       '중지(HOLDING)',       '중지(HOLDING)',       '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       3,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000032', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000032', '진행 불가(Reject)',   '진행 불가(Reject)',   '진행 불가(Reject)',   '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       4,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000033', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000033', '진행중',              '진행중',              '진행중',              '{"statusCategory":"In Progress","isDone":"N"}'::JSONB, 20,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000034', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000034', 'QA요청',              'QA요청',              'QA요청',              '{"statusCategory":"In Progress","isDone":"N"}'::JSONB, 50,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000035', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000035', '취소',                '취소',                '취소',                '{"statusCategory":"Done","isDone":"N"}'::JSONB,        90,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000036', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000036', '배포준비',            '배포준비',            '배포준비',            '{"statusCategory":"Done","isDone":"Y"}'::JSONB,        99,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
('0000000000037', (SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL) || '/0000000000037', '작업완료',            '작업완료',            '작업완료',            '{"statusCategory":"Done","isDone":"Y"}'::JSONB,        100, true, '2025-08-24 04:01:40.000000', 'SYSTEM');

-- ---------------------------------------------
-- 5. FAVORITE (즐겨찾기) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES ('0000000000005', NULL, 0, '/0000000000005', 'FAVORITE', '즐겨찾기', '즐겨찾기 사이트 모음',
        -- ⚠️ icon 은 /admin/favorites 관리 화면의 아이콘 피커가 쓴다.
        --    sensitive 를 붙이지 말 것 — 붙이면 sanitizer 가 비관리자 응답에서 url 을 걷어내
        --    공개 홈의 즐겨찾기 링크가 전부 '#' 로 죽는다 (PLATFORM 과 정반대다).
        '[{"key":"url","label":"URL","type":"text"},{"key":"icon","label":"ICON","type":"text"}]'::JSONB,
        5, true, NOW(), 'SYSTEM');

-- 즐겨찾기 카테고리 (depth=1)
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, sort, is_active, created_at, created_by)
VALUES
('0000000000038', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000038', 'COMMUNITY',  'Community',  '커뮤니티 사이트', 1, true, NOW(), 'SYSTEM'),
('0000000000039', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000039', 'MEMBERSHIP', 'Membership', '멤버십 관련',     2, true, NOW(), 'SYSTEM'),
('0000000000040', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000040', 'DEVTOOLS',   '<devTools>', '개발 도구',       3, true, NOW(), 'SYSTEM'),
('0000000000041', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000041', 'WEBTOOLS',   'WebTools',   '웹 도구',         4, true, NOW(), 'SYSTEM'),
('0000000000042', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000042', 'GEOSERVICE', 'GeoService', '위치 서비스',     5, true, NOW(), 'SYSTEM'),
('0000000000043', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000043', 'ETC',        'etc',        '기타',            6, true, NOW(), 'SYSTEM'),
('0000000000044', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000044', 'HARDWARE',   '철물점',     '철물점',          7, true, NOW(), 'SYSTEM'),
('0000000000045', (SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, (SELECT path FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL) || '/0000000000045', 'STREAMING',  'Streaming',  '스트리밍',        8, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - Community
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000046', (SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1) || '/0000000000046', 'CLIEN',           'clien',               '클리앙',        '{"url":"http://www.clien.net/"}'::JSONB,                                              1, true, NOW(), 'SYSTEM'),
('0000000000047', (SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1) || '/0000000000047', 'OKKY',            'okky',                'OKKY',          '{"url":"https://okky.kr/"}'::JSONB,                                                   2, true, NOW(), 'SYSTEM'),
('0000000000048', (SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1) || '/0000000000048', 'AAGAG',           'AAGAG',               'AAGAG',         '{"url":"https://aagag.com/mirror/?target=_blank&time=12"}'::JSONB,                     4, true, NOW(), 'SYSTEM'),
('0000000000049', (SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1) || '/0000000000049', 'PPOMPPU',         '뽐뿌',                '뽐뿌',          '{"url":"http://www.ppomppu.co.kr/"}'::JSONB,                                           5, true, NOW(), 'SYSTEM'),
('0000000000050', (SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1) || '/0000000000050', 'PPOMPPU_CAMPING', '뽐뿌 - 캠핑포럼',     '뽐뿌 캠핑포럼', '{"url":"http://m.ppomppu.co.kr/new/bbs_list.php?id=camping"}'::JSONB,                   6, true, NOW(), 'SYSTEM'),
('0000000000051', (SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1) || '/0000000000051', 'PPOMPPU_FISHING', '뽐뿌 - 낚시포럼',     '뽐뿌 낚시포럼', '{"url":"http://m.ppomppu.co.kr/new/bbs_list.php?id=fishing"}'::JSONB,                   7, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - Membership
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000052', (SELECT id FROM tb_master_code WHERE code = 'MEMBERSHIP' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'MEMBERSHIP' AND depth = 1) || '/0000000000052', 'NAVER_PLUS_LGU', '네이버플러스 X Lgu+', '네이버플러스 LGU+', '{"url":"https://nid.naver.com/membership/partner/uplus"}'::JSONB,  1, true, NOW(), 'SYSTEM'),
('0000000000053', (SELECT id FROM tb_master_code WHERE code = 'MEMBERSHIP' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'MEMBERSHIP' AND depth = 1) || '/0000000000053', 'NAVER_PLUS',     '네이버플러스',         '네이버플러스',      '{"url":"https://nid.naver.com/membership/my?m=viewSaving"}'::JSONB, 2, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - devTools
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000054', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000054', 'MAKEREADME',  'Make readme.md',           'README 생성기',      '{"url":"https://www.makeareadme.com/"}'::JSONB,                     1, true, NOW(), 'SYSTEM'),
('0000000000055', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000055', 'CODESANDBOX', 'Code Sandbox',             '코드 샌드박스',      '{"url":"https://codesandbox.io"}'::JSONB,                           2, true, NOW(), 'SYSTEM'),
('0000000000056', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000056', 'JSFIDDLE',    'Js Fiddle',                'JS Fiddle',          '{"url":"https://jsfiddle.net/user/fiddles/all/"}'::JSONB,            3, true, NOW(), 'SYSTEM'),
('0000000000057', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000057', 'ENCODING',    'Encoding',                 '인코딩 도구',        '{"url":"https://coderstoolbox.net/"}'::JSONB,                       4, true, NOW(), 'SYSTEM'),
('0000000000058', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000058', 'JSON2JAVA',   'Json to JavaClass',        'JSON to Java 변환',  '{"url":"https://codebeautify.org/json-to-java-converter"}'::JSONB,  5, true, NOW(), 'SYSTEM'),
('0000000000059', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000059', 'EPOCH',       'Epoch & Unix Timestamp',   'Epoch 시간 변환',    '{"url":"https://www.epochconverter.com/"}'::JSONB,                  6, true, NOW(), 'SYSTEM'),
('0000000000060', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000060', 'REGEX101',    'regex101(정규식 검색)',    '정규식 라이브러리',  '{"url":"https://regex101.com/library"}'::JSONB,                     7, true, NOW(), 'SYSTEM'),
('0000000000061', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000061', 'REGEXR',      '정규식 테스트',            '정규식 테스터',      '{"url":"https://regexr.com"}'::JSONB,                               8, true, NOW(), 'SYSTEM'),
('0000000000062', (SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1) || '/0000000000062', 'REGEXSTORM',  '정규식 테스트(.net)',      '.NET 정규식 테스터', '{"url":"http://regexstorm.net/tester"}'::JSONB,                     9, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - WebTools
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000063', (SELECT id FROM tb_master_code WHERE code = 'WEBTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'WEBTOOLS' AND depth = 1) || '/0000000000063', 'PHOTOPEA', '웹용 포토샵',           '웹 포토샵',   '{"url":"https://www.photopea.com"}'::JSONB,           1, true, NOW(), 'SYSTEM'),
('0000000000064', (SELECT id FROM tb_master_code WHERE code = 'WEBTOOLS' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'WEBTOOLS' AND depth = 1) || '/0000000000064', 'CIDR',     'Calc Cidr(IP대역계산)', 'CIDR 계산기', '{"url":"https://www.ipaddressguide.com/cidr"}'::JSONB, 2, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - GeoService
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000065', (SELECT id FROM tb_master_code WHERE code = 'GEOSERVICE' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'GEOSERVICE' AND depth = 1) || '/0000000000065', 'NAVER_MYPLACE', '네이버 마이플레이스', '네이버 마이플레이스', '{"url":"https://m.store.naver.com/myplace/home"}'::JSONB, 1, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - etc
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000066', (SELECT id FROM tb_master_code WHERE code = 'ETC' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'ETC' AND depth = 1) || '/0000000000066', 'VPNGATE',  'VPN Gate',                                    'VPN Gate',          '{"url":"http://www.vpngate.net/en/"}'::JSONB,                                   1, true, NOW(), 'SYSTEM'),
('0000000000067', (SELECT id FROM tb_master_code WHERE code = 'ETC' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'ETC' AND depth = 1) || '/0000000000067', 'DOSGAMES', 'Almost 2,400 DOS GAMES using PC Browsers',     'DOS 게임 아카이브', '{"url":"https://archive.org/details/softwarelibrary_msdos_games/v2"}'::JSONB,    2, true, NOW(), 'SYSTEM'),
('0000000000068', (SELECT id FROM tb_master_code WHERE code = 'ETC' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'ETC' AND depth = 1) || '/0000000000068', 'ARCADE',   'Internet Arcade',                             '인터넷 아케이드',   '{"url":"https://archive.org/details/internetarcade"}'::JSONB,                   3, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - 철물점
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000069', (SELECT id FROM tb_master_code WHERE code = 'HARDWARE' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'HARDWARE' AND depth = 1) || '/0000000000069', 'IVERANDA', '아이베란다', '아이베란다', '{"url":"http://www.iveranda.com/"}'::JSONB, 1, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - Streaming
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
('0000000000070', (SELECT id FROM tb_master_code WHERE code = 'STREAMING' AND depth = 1), 2, (SELECT path FROM tb_master_code WHERE code = 'STREAMING' AND depth = 1) || '/0000000000070', 'NUNUTV', '누누.tv', '누누TV', '{"url":"https://nunutv1.me/"}'::JSONB, 1, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 6. PLATFORM (플랫폼 링크) - 루트
--    ⚠️ 관리자 전용이다. hvy.master-code.public-roots 화이트리스트에 PLATFORM 을 절대 넣지 말 것 —
--       넣는 순간 /api/codes/tree/PLATFORM 이 비인증에게 열린다 (application.yml 의 public-roots).
--    url/icon 의 sensitive:"true" 는 그 사고가 나도 내부 호스트명만은 막기 위한 이중 방어다.
--    아래 URL 은 전부 플레이스홀더다. 실제 내부 주소는 git 에 커밋하지 않고 /admin/favorites 에서 채운다.
-- ---------------------------------------------
INSERT INTO tb_master_code (id, parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES ('0000000000006', NULL, 0, '/0000000000006', 'PLATFORM', '플랫폼', '내부 운영 플랫폼 링크 (관리자 전용)',
        '[{"key":"url","label":"URL","type":"text","sensitive":"true"},{"key":"icon","label":"ICON","type":"text","sensitive":"true"}]'::JSONB,
        6, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- path 는 각 INSERT 가 직접 넣는다 (예전의 일괄 UPDATE 제거).
-- id 가 애플리케이션 생성 TSID 문자열이라 INSERT 시점에 이미 알고 있기 때문이다.
-- ⚠️ 이 블록을 되살리지 말 것 — 숫자 id 기반이라 TSID path 를 전부 망가뜨린다.
-- ---------------------------------------------

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
INSERT INTO tb_master_code (parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES (NULL, 0, NULL, 'REGION', '지역분류', '대한민국 지역 분류 코드',
        '[{"key":"latitude","label":"위도","type":"text"},{"key":"longitude","label":"경도","type":"text"},{"key":"population","label":"인구수","type":"text"},{"key":"area","label":"면적","type":"text"},{"key":"zipCode","label":"우편번호","type":"text"}]'::JSONB,
        1, true, NOW(), 'SYSTEM');

-- 지역 코드 (depth=1) - path는 트리거 없이 INSERT 후 UPDATE로 설정
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, 'SEOUL',   '서울특별시', '대한민국의 수도',    '{"latitude":"37.5665","longitude":"126.9780","population":"9720846","area":"605.21","zipCode":"04500"}'::JSONB, 1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, 'BUSAN',   '부산광역시', '대한민국의 제2도시',  '{"latitude":"35.1796","longitude":"129.0756","population":"3448737","area":"769.82","zipCode":"48058"}'::JSONB, 2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, 'INCHEON', '인천광역시', '대한민국의 관문도시', '{"latitude":"37.4563","longitude":"126.7052","population":"2947217","area":"1065.4","zipCode":"21554"}'::JSONB, 3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'REGION' AND parent_id IS NULL), 1, 'DAEGU',   '대구광역시', '대한민국의 섬유도시', '{"latitude":"35.8714","longitude":"128.6014","population":"2410700","area":"883.56","zipCode":"41911"}'::JSONB, 4, true, NOW(), 'SYSTEM');

-- 서울 구 코드 (depth=2)
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, 'GANGNAM', '강남구', '서울의 강남 지역', '{"latitude":"37.5172","longitude":"127.0473","population":"569901","area":"39.50","zipCode":"06028"}'::JSONB, 1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, 'SEOCHO',  '서초구', '서울의 서초 지역', '{"latitude":"37.4837","longitude":"127.0324","population":"433453","area":"47.00","zipCode":"06593"}'::JSONB, 2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, 'SONGPA',  '송파구', '서울의 송파 지역', '{"latitude":"37.5145","longitude":"127.1059","population":"686489","area":"33.88","zipCode":"05505"}'::JSONB, 3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'SEOUL' AND depth = 1), 2, 'JONGNO',  '종로구', '서울의 중심 지역', '{"latitude":"37.5735","longitude":"126.9788","population":"162820","area":"23.91","zipCode":"03045"}'::JSONB, 4, true, NOW(), 'SYSTEM');

-- 부산 구 코드 (depth=2)
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, 'HAEUNDAE', '해운대구', '부산의 관광 중심지', '{"latitude":"35.1631","longitude":"129.1640","population":"411349","area":"51.44","zipCode":"48059"}'::JSONB, 1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, 'SAHA',     '사하구',   '부산의 서쪽 지역',   '{"latitude":"35.1041","longitude":"128.9743","population":"334957","area":"40.89","zipCode":"49424"}'::JSONB, 2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, 'BUSANJIN', '부산진구', '부산의 중심 지역',   '{"latitude":"35.1630","longitude":"129.0531","population":"384593","area":"29.70","zipCode":"47176"}'::JSONB, 3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'BUSAN' AND depth = 1), 2, 'DONGNAE',  '동래구',   '부산의 전통 온천지', '{"latitude":"35.2046","longitude":"129.0840","population":"270748","area":"16.63","zipCode":"47809"}'::JSONB, 4, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 2. STATUS (상태분류) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (parent_id, depth, path, code, name, description, sort, is_active, created_at, created_by)
VALUES (NULL, 0, NULL, 'STATUS', '상태분류', '일반적인 상태 코드', 2, true, NOW(), 'SYSTEM');

INSERT INTO tb_master_code (parent_id, depth, code, name, description, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, 'ACTIVE',    '활성',   '활성화된 상태',   1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, 'INACTIVE',  '비활성', '비활성화된 상태', 2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, 'PENDING',   '대기',   '처리 대기 상태', 3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, 'COMPLETED', '완료',   '처리 완료 상태', 4, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'STATUS' AND parent_id IS NULL), 1, 'CANCELLED', '취소',   '취소된 상태',    5, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 3. CATEGORY (카테고리분류) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES (NULL, 0, NULL, 'CATEGORY', '카테고리분류', '일반 카테고리 분류',
        '[{"key":"color","label":"색상코드","type":"text"},{"key":"icon","label":"아이콘","type":"text"},{"key":"order","label":"순서","type":"text"}]'::JSONB,
        3, true, NOW(), 'SYSTEM');

INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, 'TECH',      '기술',         'IT 및 기술 관련',  '{"color":"#007bff","icon":"fas fa-laptop-code","order":"1"}'::JSONB,    1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, 'BUSINESS',  '비즈니스',     '비즈니스 및 경영', '{"color":"#28a745","icon":"fas fa-briefcase","order":"2"}'::JSONB,      2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, 'DESIGN',    '디자인',       '디자인 및 창작',   '{"color":"#dc3545","icon":"fas fa-palette","order":"3"}'::JSONB,        3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, 'EDUCATION', '교육',         '교육 및 학습',     '{"color":"#ffc107","icon":"fas fa-graduation-cap","order":"4"}'::JSONB, 4, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'CATEGORY' AND parent_id IS NULL), 1, 'LIFESTYLE', '라이프스타일', '일상 및 취미',     '{"color":"#6f42c1","icon":"fas fa-heart","order":"5"}'::JSONB,          5, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- 4. JIRA_STATUS (지라 상태 코드) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES (NULL, 0, NULL, 'JIRA_STATUS', '지라 상태 코드', '지라 상태 코드',
        '[{"key":"statusCategory","label":"상태카테고리","type":"text"},{"key":"isDone","label":"완료여부","type":"text"}]'::JSONB,
        4, true, '2025-08-24 04:01:40.000000', 'SYSTEM');

INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '해야 할 일(작업요청)', '해야 할 일(작업요청)', '해야 할 일(작업요청)', '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       1,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '작업예정(요청확인)',   '작업예정(요청확인)',   '작업예정(요청확인)',   '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       2,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '중지(HOLDING)',       '중지(HOLDING)',       '중지(HOLDING)',       '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       3,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '진행 불가(Reject)',   '진행 불가(Reject)',   '진행 불가(Reject)',   '{"statusCategory":"To Do","isDone":"N"}'::JSONB,       4,   true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '진행중',              '진행중',              '진행중',              '{"statusCategory":"In Progress","isDone":"N"}'::JSONB, 20,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, 'QA요청',              'QA요청',              'QA요청',              '{"statusCategory":"In Progress","isDone":"N"}'::JSONB, 50,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '취소',                '취소',                '취소',                '{"statusCategory":"Done","isDone":"N"}'::JSONB,        90,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '배포준비',            '배포준비',            '배포준비',            '{"statusCategory":"Done","isDone":"Y"}'::JSONB,        99,  true, '2025-08-24 04:01:40.000000', 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'JIRA_STATUS' AND parent_id IS NULL), 1, '작업완료',            '작업완료',            '작업완료',            '{"statusCategory":"Done","isDone":"Y"}'::JSONB,        100, true, '2025-08-24 04:01:40.000000', 'SYSTEM');

-- ---------------------------------------------
-- 5. FAVORITE (즐겨찾기) - 루트
-- ---------------------------------------------
INSERT INTO tb_master_code (parent_id, depth, path, code, name, description, attribute_schema, sort, is_active, created_at, created_by)
VALUES (NULL, 0, NULL, 'FAVORITE', '즐겨찾기', '즐겨찾기 사이트 모음',
        '[{"key":"url","label":"URL","type":"text"}]'::JSONB,
        5, true, NOW(), 'SYSTEM');

-- 즐겨찾기 카테고리 (depth=1)
INSERT INTO tb_master_code (parent_id, depth, code, name, description, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'COMMUNITY',  'Community',  '커뮤니티 사이트', 1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'MEMBERSHIP', 'Membership', '멤버십 관련',     2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'DEVTOOLS',   '<devTools>', '개발 도구',       3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'WEBTOOLS',   'WebTools',   '웹 도구',         4, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'GEOSERVICE', 'GeoService', '위치 서비스',     5, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'ETC',        'etc',        '기타',            6, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'HARDWARE',   '철물점',     '철물점',          7, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'FAVORITE' AND parent_id IS NULL), 1, 'STREAMING',  'Streaming',  '스트리밍',        8, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - Community
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, 'CLIEN',           'clien',               '클리앙',        '{"url":"http://www.clien.net/"}'::JSONB,                                              1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, 'OKKY',            'okky',                'OKKY',          '{"url":"https://okky.kr/"}'::JSONB,                                                   2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, 'AAGAG',           'AAGAG',               'AAGAG',         '{"url":"https://aagag.com/mirror/?target=_blank&time=12"}'::JSONB,                     4, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, 'PPOMPPU',         '뽐뿌',                '뽐뿌',          '{"url":"http://www.ppomppu.co.kr/"}'::JSONB,                                           5, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, 'PPOMPPU_CAMPING', '뽐뿌 - 캠핑포럼',     '뽐뿌 캠핑포럼', '{"url":"http://m.ppomppu.co.kr/new/bbs_list.php?id=camping"}'::JSONB,                   6, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'COMMUNITY' AND depth = 1), 2, 'PPOMPPU_FISHING', '뽐뿌 - 낚시포럼',     '뽐뿌 낚시포럼', '{"url":"http://m.ppomppu.co.kr/new/bbs_list.php?id=fishing"}'::JSONB,                   7, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - Membership
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'MEMBERSHIP' AND depth = 1), 2, 'NAVER_PLUS_LGU', '네이버플러스 X Lgu+', '네이버플러스 LGU+', '{"url":"https://nid.naver.com/membership/partner/uplus"}'::JSONB,  1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'MEMBERSHIP' AND depth = 1), 2, 'NAVER_PLUS',     '네이버플러스',         '네이버플러스',      '{"url":"https://nid.naver.com/membership/my?m=viewSaving"}'::JSONB, 2, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - devTools
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'MAKEREADME',  'Make readme.md',           'README 생성기',      '{"url":"https://www.makeareadme.com/"}'::JSONB,                     1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'CODESANDBOX', 'Code Sandbox',             '코드 샌드박스',      '{"url":"https://codesandbox.io"}'::JSONB,                           2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'JSFIDDLE',    'Js Fiddle',                'JS Fiddle',          '{"url":"https://jsfiddle.net/user/fiddles/all/"}'::JSONB,            3, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'ENCODING',    'Encoding',                 '인코딩 도구',        '{"url":"https://coderstoolbox.net/"}'::JSONB,                       4, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'JSON2JAVA',   'Json to JavaClass',        'JSON to Java 변환',  '{"url":"https://codebeautify.org/json-to-java-converter"}'::JSONB,  5, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'EPOCH',       'Epoch & Unix Timestamp',   'Epoch 시간 변환',    '{"url":"https://www.epochconverter.com/"}'::JSONB,                  6, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'REGEX101',    'regex101(정규식 검색)',    '정규식 라이브러리',  '{"url":"https://regex101.com/library"}'::JSONB,                     7, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'REGEXR',      '정규식 테스트',            '정규식 테스터',      '{"url":"https://regexr.com"}'::JSONB,                               8, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'DEVTOOLS' AND depth = 1), 2, 'REGEXSTORM',  '정규식 테스트(.net)',      '.NET 정규식 테스터', '{"url":"http://regexstorm.net/tester"}'::JSONB,                     9, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - WebTools
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'WEBTOOLS' AND depth = 1), 2, 'PHOTOPEA', '웹용 포토샵',           '웹 포토샵',   '{"url":"https://www.photopea.com"}'::JSONB,           1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'WEBTOOLS' AND depth = 1), 2, 'CIDR',     'Calc Cidr(IP대역계산)', 'CIDR 계산기', '{"url":"https://www.ipaddressguide.com/cidr"}'::JSONB, 2, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - GeoService
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'GEOSERVICE' AND depth = 1), 2, 'NAVER_MYPLACE', '네이버 마이플레이스', '네이버 마이플레이스', '{"url":"https://m.store.naver.com/myplace/home"}'::JSONB, 1, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - etc
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'ETC' AND depth = 1), 2, 'VPNGATE',  'VPN Gate',                                    'VPN Gate',          '{"url":"http://www.vpngate.net/en/"}'::JSONB,                                   1, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'ETC' AND depth = 1), 2, 'DOSGAMES', 'Almost 2,400 DOS GAMES using PC Browsers',     'DOS 게임 아카이브', '{"url":"https://archive.org/details/softwarelibrary_msdos_games/v2"}'::JSONB,    2, true, NOW(), 'SYSTEM'),
((SELECT id FROM tb_master_code WHERE code = 'ETC' AND depth = 1), 2, 'ARCADE',   'Internet Arcade',                             '인터넷 아케이드',   '{"url":"https://archive.org/details/internetarcade"}'::JSONB,                   3, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - 철물점
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'HARDWARE' AND depth = 1), 2, 'IVERANDA', '아이베란다', '아이베란다', '{"url":"http://www.iveranda.com/"}'::JSONB, 1, true, NOW(), 'SYSTEM');

-- 즐겨찾기 사이트 (depth=2) - Streaming
INSERT INTO tb_master_code (parent_id, depth, code, name, description, attributes, sort, is_active, created_at, created_by)
VALUES
((SELECT id FROM tb_master_code WHERE code = 'STREAMING' AND depth = 1), 2, 'NUNUTV', '누누.tv', '누누TV', '{"url":"https://nunutv1.me/"}'::JSONB, 1, true, NOW(), 'SYSTEM');

-- ---------------------------------------------
-- path 일괄 업데이트 (INSERT 후 ID 확정 후 설정)
-- ---------------------------------------------
UPDATE tb_master_code SET path = '/' || id::TEXT WHERE depth = 0;
UPDATE tb_master_code c SET path = (SELECT p.path FROM tb_master_code p WHERE p.id = c.parent_id) || '/' || c.id::TEXT WHERE depth = 1;
UPDATE tb_master_code c SET path = (SELECT p.path FROM tb_master_code p WHERE p.id = c.parent_id) || '/' || c.id::TEXT WHERE depth = 2;

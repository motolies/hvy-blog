INSERT INTO tb_user (id, loginId, password, name, isEnabled)
SELECT 1,
       'admin',
       '$2a$10$4Yx00Kexb3IShV37fapj..5AgIHCtLa2yLokdo0SBdTTAo/MTs0I6', -- bye
       'motolies',
       1
WHERE NOT EXISTS (SELECT 1
                  FROM tb_user
                  WHERE loginId = 'admin');

INSERT INTO tb_authority (id, name)
SELECT 1, 'ROLE_ADMIN'
WHERE NOT EXISTS (SELECT 1
                  FROM tb_authority
                  WHERE name = 'ROLE_ADMIN');

INSERT INTO tb_authority (id, name)
SELECT 2, 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1
                  FROM tb_authority
                  WHERE name = 'ROLE_USER');

INSERT INTO tb_user_authority_map (userId, authorityId)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1
                  FROM tb_user_authority_map
                  WHERE userId = 1
                    AND authorityId = 1);

INSERT INTO tb_user_authority_map (userId, authorityId)
SELECT 1, 2
WHERE NOT EXISTS (SELECT 1
                  FROM tb_user_authority_map
                  WHERE userId = 1
                    AND authorityId = 2);
select 1;
INSERT INTO tb_category (seq, id, name, fullName, fullPath, parentId)
VALUES (0, 'ROOT', '전체글', '/전체글/', '/ROOT/', null);


INSERT INTO tb_search_engine (name, url, seq, createdAt, updatedAt)
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

-- 공통코드 테스트 데이터

-- 1. 공통코드 클래스 생성
INSERT INTO tb_common_class (name, displayName, description, attribute1Name, attribute2Name, attribute3Name, attribute4Name, attribute5Name, isActive, createdAt, createdBy)
VALUES
('REGION_CLASS', '지역분류', '대한민국 지역 분류 코드', '위도', '경도', '인구수', '면적', '우편번호', 1, NOW(), 'SYSTEM'),
('SEOUL_DISTRICT_CLASS', '서울구분류', '서울특별시 구 분류', '위도', '경도', '인구수', '면적', '우편번호', 1, NOW(), 'SYSTEM'),
('BUSAN_DISTRICT_CLASS', '부산구분류', '부산광역시 구 분류', '위도', '경도', '인구수', '면적', '우편번호', 1, NOW(), 'SYSTEM'),
('STATUS_CLASS', '상태분류', '일반적인 상태 코드', '', '', '', '', '', 1, NOW(), 'SYSTEM'),
('CATEGORY_CLASS', '카테고리분류', '일반 카테고리 분류', '색상코드', '아이콘', '순서', '', '', 1, NOW(), 'SYSTEM');

-- 2. 지역 코드 (1단계)
INSERT INTO tb_common_code (className, code, name, description, attribute1Value, attribute2Value, attribute3Value, attribute4Value, attribute5Value, childClassName, sort, isActive, createdAt, createdBy)
VALUES
('REGION_CLASS', 'SEOUL', '서울특별시', '대한민국의 수도', '37.5665', '126.9780', '9720846', '605.21', '04500', 'SEOUL_DISTRICT_CLASS', 1, 1, NOW(), 'SYSTEM'),
('REGION_CLASS', 'BUSAN', '부산광역시', '대한민국의 제2도시', '35.1796', '129.0756', '3448737', '769.82', '48058', 'BUSAN_DISTRICT_CLASS', 2, 1, NOW(), 'SYSTEM'),
('REGION_CLASS', 'INCHEON', '인천광역시', '대한민국의 관문도시', '37.4563', '126.7052', '2947217', '1065.4', '21554', NULL, 3, 1, NOW(), 'SYSTEM'),
('REGION_CLASS', 'DAEGU', '대구광역시', '대한민국의 섬유도시', '35.8714', '128.6014', '2410700', '883.56', '41911', NULL, 4, 1, NOW(), 'SYSTEM');

-- 3. 서울 구 코드 (2단계)
INSERT INTO tb_common_code (className, code, name, description, attribute1Value, attribute2Value, attribute3Value, attribute4Value, attribute5Value, childClassName, sort, isActive, createdAt, createdBy)
VALUES
('SEOUL_DISTRICT_CLASS', 'GANGNAM', '강남구', '서울의 강남 지역', '37.5172', '127.0473', '569901', '39.50', '06028', NULL, 1, 1, NOW(), 'SYSTEM'),
('SEOUL_DISTRICT_CLASS', 'SEOCHO', '서초구', '서울의 서초 지역', '37.4837', '127.0324', '433453', '47.00', '06593', NULL, 2, 1, NOW(), 'SYSTEM'),
('SEOUL_DISTRICT_CLASS', 'SONGPA', '송파구', '서울의 송파 지역', '37.5145', '127.1059', '686489', '33.88', '05505', NULL, 3, 1, NOW(), 'SYSTEM'),
('SEOUL_DISTRICT_CLASS', 'JONGNO', '종로구', '서울의 중심 지역', '37.5735', '126.9788', '162820', '23.91', '03045', NULL, 4, 1, NOW(), 'SYSTEM');

-- 4. 부산 구 코드 (2단계)
INSERT INTO tb_common_code (className, code, name, description, attribute1Value, attribute2Value, attribute3Value, attribute4Value, attribute5Value, childClassName, sort, isActive, createdAt, createdBy)
VALUES
('BUSAN_DISTRICT_CLASS', 'HAEUNDAE', '해운대구', '부산의 관광 중심지', '35.1631', '129.1640', '411349', '51.44', '48059', NULL, 1, 1, NOW(), 'SYSTEM'),
('BUSAN_DISTRICT_CLASS', 'SAHA', '사하구', '부산의 서쪽 지역', '35.1041', '128.9743', '334957', '40.89', '49424', NULL, 2, 1, NOW(), 'SYSTEM'),
('BUSAN_DISTRICT_CLASS', 'BUSANJIN', '부산진구', '부산의 중심 지역', '35.1630', '129.0531', '384593', '29.70', '47176', NULL, 3, 1, NOW(), 'SYSTEM'),
('BUSAN_DISTRICT_CLASS', 'DONGNAE', '동래구', '부산의 전통 온천지', '35.2046', '129.0840', '270748', '16.63', '47809', NULL, 4, 1, NOW(), 'SYSTEM');

-- 5. 상태 코드 (단일 레벨)
INSERT INTO tb_common_code (className, code, name, description, childClassName, sort, isActive, createdAt, createdBy)
VALUES
('STATUS_CLASS', 'ACTIVE', '활성', '활성화된 상태', NULL, 1, 1, NOW(), 'SYSTEM'),
('STATUS_CLASS', 'INACTIVE', '비활성', '비활성화된 상태', NULL, 2, 1, NOW(), 'SYSTEM'),
('STATUS_CLASS', 'PENDING', '대기', '처리 대기 상태', NULL, 3, 1, NOW(), 'SYSTEM'),
('STATUS_CLASS', 'COMPLETED', '완료', '처리 완료 상태', NULL, 4, 1, NOW(), 'SYSTEM'),
('STATUS_CLASS', 'CANCELLED', '취소', '취소된 상태', NULL, 5, 1, NOW(), 'SYSTEM');

-- 6. 카테고리 코드 (속성 예시)
INSERT INTO tb_common_code (className, code, name, description, attribute1Value, attribute2Value, attribute3Value, childClassName, sort, isActive, createdAt, createdBy)
VALUES
('CATEGORY_CLASS', 'TECH', '기술', 'IT 및 기술 관련', '#007bff', 'fas fa-laptop-code', '1', NULL, 1, 1, NOW(), 'SYSTEM'),
('CATEGORY_CLASS', 'BUSINESS', '비즈니스', '비즈니스 및 경영', '#28a745', 'fas fa-briefcase', '2', NULL, 2, 1, NOW(), 'SYSTEM'),
('CATEGORY_CLASS', 'DESIGN', '디자인', '디자인 및 창작', '#dc3545', 'fas fa-palette', '3', NULL, 3, 1, NOW(), 'SYSTEM'),
('CATEGORY_CLASS', 'EDUCATION', '교육', '교육 및 학습', '#ffc107', 'fas fa-graduation-cap', '4', NULL, 4, 1, NOW(), 'SYSTEM'),
('CATEGORY_CLASS', 'LIFESTYLE', '라이프스타일', '일상 및 취미', '#6f42c1', 'fas fa-heart', '5', NULL, 5, 1, NOW(), 'SYSTEM');


INSERT INTO blog.tb_common_class (name, displayName, description, attribute1Name, attribute2Name, attribute3Name, attribute4Name, attribute5Name, isActive, createdAt, createdBy, updatedAt, updatedBy)
VALUES ('JIRA_STATUS', '지라 상태 코드', '지라 상태 코드', 'statusCategory', 'sprintYn', null, null, null, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null);

INSERT INTO blog.tb_common_code (className, code, name, description, attribute1Value, attribute2Value, attribute3Value, attribute4Value, attribute5Value, childClassName, sort, isActive, createdAt, createdBy, updatedAt, updatedBy)
VALUES ('JIRA_STATUS', '해야 할 일(작업요청)', '해야 할 일(작업요청)', '해야 할 일(작업요청)', 'To Do', 'N', null, null, null, null, 1, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '작업예정(요청확인)', '작업예정(요청확인)', '작업예정(요청확인)', 'To Do', 'N', null, null, null, null, 2, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '중지(HOLDING)', '중지(HOLDING)', '중지(HOLDING)', 'To Do', 'N', null, null, null, null, 3, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '진행 불가(Reject)', '진행 불가(Reject)', '진행 불가(Reject)', 'To Do', 'N', null, null, null, null, 4, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '진행중', '진행중', '진행중', 'In Progress', 'N', null, null, null, null, 20, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', 'QA요청', 'QA요청', 'QA요청', 'In Progress', 'N', null, null, null, null, 50, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '취소', '취소', '취소', 'Done', 'N', null, null, null, null, 90, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '배포준비', '배포준비', '배포준비', 'Done', 'Y', null, null, null, null, 99, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null)
,('JIRA_STATUS', '작업완료', '작업완료', '작업완료', 'Done', 'Y', null, null, null, null, 100, true, '2025-08-24 04:01:40.000000', 'SYSTEM', null, null);

-- 즐겨찾기 관리 공통코드

-- 1. 즐겨찾기 공통코드 클래스 생성
INSERT INTO tb_common_class (code, name, description, attribute1Name, isActive, createdAt, createdBy)
VALUES
('FAVORITE_ROOT', '즐겨찾기 루트', '즐겨찾기 최상위 분류', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_CATEGORY', '즐겨찾기 카테고리', '즐겨찾기 카테고리 분류', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_COMMUNITY_SITES', 'Community 사이트', 'Community 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_MEMBERSHIP_SITES', 'Membership 사이트', 'Membership 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_DEVTOOLS_SITES', 'devTools 사이트', 'devTools 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_WEBTOOLS_SITES', 'WebTools 사이트', 'WebTools 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_GEOSERVICE_SITES', 'GeoService 사이트', 'GeoService 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_ETC_SITES', 'etc 사이트', 'etc 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_HARDWARE_SITES', '철물점 사이트', '철물점 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM'),
('FAVORITE_STREAMING_SITES', 'Streaming 사이트', 'Streaming 카테고리 사이트', 'url', 1, NOW(), 'SYSTEM');

-- 2. 즐겨찾기 루트 코드 (1단계)
INSERT INTO tb_common_code (classId, code, name, description, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_ROOT'), 'ROOT', '즐겨찾기', '즐겨찾기 루트', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 1, 1, NOW(), 'SYSTEM');

-- 3. 즐겨찾기 카테고리 코드 (2단계)
INSERT INTO tb_common_code (classId, code, name, description, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'COMMUNITY', 'Community', '커뮤니티 사이트', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 1, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'MEMBERSHIP', 'Membership', '멤버십 관련', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_MEMBERSHIP_SITES'), 2, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'DEVTOOLS', '<devTools>', '개발 도구', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 3, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'WEBTOOLS', 'WebTools', '웹 도구', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_WEBTOOLS_SITES'), 4, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'GEOSERVICE', 'GeoService', '위치 서비스', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_GEOSERVICE_SITES'), 5, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'ETC', 'etc', '기타', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_ETC_SITES'), 6, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'HARDWARE', '철물점', '철물점', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_HARDWARE_SITES'), 7, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_CATEGORY'), 'STREAMING', 'Streaming', '스트리밍', (SELECT id FROM tb_common_class WHERE code = 'FAVORITE_STREAMING_SITES'), 8, 1, NOW(), 'SYSTEM');

-- 4. 즐겨찾기 사이트 코드 (3단계) - Community
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 'CLIEN', 'clien', '클리앙', 'http://www.clien.net/', NULL, 1, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 'OKKY', 'okky', 'OKKY', 'https://okky.kr/', NULL, 2, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 'AAGAG', 'AAGAG', 'AAGAG', 'https://aagag.com/mirror/?target=_blank&time=12', NULL, 4, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 'PPOMPPU', '뽐뿌', '뽐뿌', 'http://www.ppomppu.co.kr/', NULL, 5, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 'PPOMPPU_CAMPING', '뽐뿌 - 캠핑포럼', '뽐뿌 캠핑포럼', 'http://m.ppomppu.co.kr/new/bbs_list.php?id=camping', NULL, 6, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_COMMUNITY_SITES'), 'PPOMPPU_FISHING', '뽐뿌 - 낚시포럼', '뽐뿌 낚시포럼', 'http://m.ppomppu.co.kr/new/bbs_list.php?id=fishing', NULL, 7, 1, NOW(), 'SYSTEM');

-- 5. 즐겨찾기 사이트 코드 (3단계) - Membership
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_MEMBERSHIP_SITES'), 'NAVER_PLUS_LGU', '네이버플러스 X Lgu+', '네이버플러스 LGU+', 'https://nid.naver.com/membership/partner/uplus', NULL, 1, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_MEMBERSHIP_SITES'), 'NAVER_PLUS', '네이버플러스', '네이버플러스', 'https://nid.naver.com/membership/my?m=viewSaving', NULL, 2, 1, NOW(), 'SYSTEM');

-- 6. 즐겨찾기 사이트 코드 (3단계) - devTools
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'MAKEREADME', 'Make readme.md', 'README 생성기', 'https://www.makeareadme.com/', NULL, 1, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'CODESANDBOX', 'Code Sandbox', '코드 샌드박스', 'https://codesandbox.io', NULL, 2, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'JSFIDDLE', 'Js Fiddle', 'JS Fiddle', 'https://jsfiddle.net/user/fiddles/all/', NULL, 3, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'ENCODING', 'Encoding', '인코딩 도구', 'https://coderstoolbox.net/', NULL, 4, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'JSON2JAVA', 'Json to JavaClass', 'JSON to Java 변환', 'https://codebeautify.org/json-to-java-converter', NULL, 5, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'EPOCH', 'Epoch & Unix Timestamp', 'Epoch 시간 변환', 'https://www.epochconverter.com/', NULL, 6, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'REGEX101', 'regex101(정규식 검색)', '정규식 라이브러리', 'https://regex101.com/library', NULL, 7, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'REGEXR', '정규식 테스트', '정규식 테스터', 'https://regexr.com', NULL, 8, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_DEVTOOLS_SITES'), 'REGEXSTORM', '정규식 테스트(.net)', '.NET 정규식 테스터', 'http://regexstorm.net/tester', NULL, 9, 1, NOW(), 'SYSTEM');

-- 7. 즐겨찾기 사이트 코드 (3단계) - WebTools
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_WEBTOOLS_SITES'), 'PHOTOPEA', '웹용 포토샵', '웹 포토샵', 'https://www.photopea.com', NULL, 1, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_WEBTOOLS_SITES'), 'CIDR', 'Calc Cidr(IP대역계산)', 'CIDR 계산기', 'https://www.ipaddressguide.com/cidr', NULL, 2, 1, NOW(), 'SYSTEM');

-- 8. 즐겨찾기 사이트 코드 (3단계) - GeoService
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_GEOSERVICE_SITES'), 'NAVER_MYPLACE', '네이버 마이플레이스', '네이버 마이플레이스', 'https://m.store.naver.com/myplace/home', NULL, 1, 1, NOW(), 'SYSTEM');

-- 9. 즐겨찾기 사이트 코드 (3단계) - etc
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_ETC_SITES'), 'VPNGATE', 'VPN Gate', 'VPN Gate', 'http://www.vpngate.net/en/', NULL, 1, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_ETC_SITES'), 'DOSGAMES', 'Almost 2,400 DOS GAMES using PC Browsers', 'DOS 게임 아카이브', 'https://archive.org/details/softwarelibrary_msdos_games/v2', NULL, 2, 1, NOW(), 'SYSTEM'),
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_ETC_SITES'), 'ARCADE', 'Internet Arcade', '인터넷 아케이드', 'https://archive.org/details/internetarcade', NULL, 3, 1, NOW(), 'SYSTEM');

-- 10. 즐겨찾기 사이트 코드 (3단계) - 철물점
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_HARDWARE_SITES'), 'IVERANDA', '아이베란다', '아이베란다', 'http://www.iveranda.com/', NULL, 1, 1, NOW(), 'SYSTEM');

-- 11. 즐겨찾기 사이트 코드 (3단계) - Streaming
INSERT INTO tb_common_code (classId, code, name, description, attribute1Value, childClassId, sort, isActive, createdAt, createdBy)
VALUES
((SELECT id FROM tb_common_class WHERE code = 'FAVORITE_STREAMING_SITES'), 'NUNUTV', '누누.tv', '누누TV', 'https://nunutv1.me/', NULL, 1, 1, NOW(), 'SYSTEM');


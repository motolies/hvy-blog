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
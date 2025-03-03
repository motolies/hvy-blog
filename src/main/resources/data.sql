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
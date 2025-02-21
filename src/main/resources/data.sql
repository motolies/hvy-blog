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


INSERT INTO tb_search_engine (Name, Url, `Order`, createdAt, updatedAt) VALUES ('Naver', 'http://search.naver.com/search.naver?sm=tab_hty.top&where=nexearch&ie=utf8&query=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24')
,('Daum', 'http://search.daum.net/search?w=tot&DA=YZR&t__nil_searchbox=btn&sug=&sugo=&q=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24')
,('Google', 'https://www.google.com/search?gl=US&num=100&newwindow=1&tbs=&q=%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24')
,('Google Image', 'https://www.google.com/search?gl=US&biw=1920&bih=955&tbm=isch&sa=1&btnG=%EA%B2%80%EC%83%89&q=%s&oq=&gs_l=', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24')
,('Google Cache', 'http://webcache.googleusercontent.com/search?q=cache:%s', 0, '2022-05-30 06:14:24', '2022-05-30 06:14:24');

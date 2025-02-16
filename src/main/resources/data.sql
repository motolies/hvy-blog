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
INSERT INTO tb_category (`Order`, Id, Name, FullName, FullPath, ParentId)
VALUES (0, 'ROOT', '전체글', '/전체글/', '/ROOT/', null)
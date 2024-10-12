INSERT INTO users (Id, login_id, password, name, is_enabled)
SELECT 1,
       'admin',
       '$2a$10$4Yx00Kexb3IShV37fapj..5AgIHCtLa2yLokdo0SBdTTAo/MTs0I6', -- bye
       'motolies',
       1
WHERE NOT EXISTS (SELECT 1
                  FROM users
                  WHERE login_id = 'admin');

INSERT INTO authority (id, name)
SELECT 1, 'ROLE_ADMIN'
WHERE NOT EXISTS (SELECT 1
                  FROM authority
                  WHERE name = 'ROLE_ADMIN');

INSERT INTO authority (id, name)
SELECT 2, 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1
                  FROM authority
                  WHERE name = 'ROLE_USER');

INSERT INTO user_authority_map (user_id, authority_id)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1
                  FROM user_authority_map
                  WHERE user_id = 1
                    AND authority_id = 1);

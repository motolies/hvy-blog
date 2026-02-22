DROP FUNCTION IF EXISTS usp_category_fullname_update();

CREATE OR REPLACE FUNCTION usp_category_fullname_update()
    RETURNS void
    LANGUAGE plpgsql
AS
$$
DECLARE
    mCur INTEGER;
    mMax INTEGER;
BEGIN
    mCur := 1;

    DROP TABLE IF EXISTS tmp_category;
    CREATE TEMPORARY TABLE tmp_category
    (
        id        VARCHAR(32)  NOT NULL,
        name      VARCHAR(64)  NOT NULL,
        seq       INTEGER      NOT NULL,
        full_name VARCHAR(512) NOT NULL,
        full_path VARCHAR(512) NOT NULL,
        parent_id VARCHAR(32),
        level     INTEGER      NOT NULL
    ) ON COMMIT DROP;

    INSERT INTO tmp_category (id, name, seq, parent_id, full_name, full_path, level)
    WITH RECURSIVE cte AS (
        SELECT id, name, seq, parent_id, full_name, full_path, 0 AS level
        FROM tb_category
        WHERE parent_id IS NULL
        UNION ALL
        SELECT aa.id, aa.name, aa.seq, aa.parent_id, aa.full_name, aa.full_path, bb.level + 1 AS level
        FROM tb_category AS aa
                 INNER JOIN cte AS bb ON aa.parent_id = bb.id
    )
    SELECT id, name, seq, parent_id, full_name, full_path, level
    FROM cte
    ORDER BY full_name, seq;

    SELECT MAX(level) + 1 INTO mMax FROM tmp_category;

    WHILE mCur < mMax LOOP

        UPDATE tb_category c
        SET full_name = CONCAT(p.full_name, c.name, '/')
        FROM tb_category p
        WHERE c.parent_id = p.id
          AND c.id IN (SELECT id FROM tmp_category WHERE level = mCur);

        UPDATE tb_category c
        SET full_path = CONCAT(p.full_path, c.id, '/')
        FROM tb_category p
        WHERE c.parent_id = p.id
          AND c.id IN (SELECT id FROM tmp_category WHERE level = mCur);

        mCur := mCur + 1;

    END LOOP;

END;
$$;

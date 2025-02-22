DELIMITER $$

USE `blog`$$

DROP PROCEDURE IF EXISTS usp_category_fullname_update$$
create procedure usp_category_fullname_update() comment 'tb_category fullName 정리'
BEGIN

	DECLARE mCur INT;
	DECLARE mMax INT;

	SET mCur = 1;

	DROP TEMPORARY TABLE IF EXISTS tmpCategory;
	CREATE TEMPORARY TABLE tmpCategory
	(
		  id       VARCHAR(32) NOT NULL,
		  name     VARCHAR(64) NOT NULL,
		  seq  INT(11) NOT NULL,
		  fullName VARCHAR(512) NOT NULL,
		  fullPath VARCHAR(512) NOT NULL,
		  parentId      VARCHAR(32),
		  level		INT(11) NOT NULL
	) ENGINE = MEMORY;

	INSERT INTO tmpCategory(id, `name`, seq, parentId, fullName, fullPath, level)
	WITH RECURSIVE cte
	AS
	(
		SELECT id, `name`, seq, parentId, fullName, fullPath, 0 AS level
		FROM tb_category
		WHERE parentId IS NULL
		UNION ALL
		SELECT AA.id, AA.`name`, AA.seq, AA.parentId, AA.fullName, AA.fullPath, BB.level+1 AS level
		FROM tb_category AS AA
		   INNER JOIN cte AS BB ON AA.parentId = BB.id
	)
	SELECT id, `name`, seq, parentId, fullName, fullPath, level
	FROM cte
	ORDER BY fullname, seq;

	SELECT MAX(level) + 1 INTO mMax FROM tmpCategory;

	WHILE mCur < mMax DO

		UPDATE tb_category c
			LEFT JOIN tb_category p ON c.`parentId` = p.`id`
			SET c.`fullName` = CONCAT(p.`fullName`, c.`name`, '/')
		WHERE c.`id` IN(
			SELECT id FROM tmpCategory WHERE level = mCur
		);
		
		UPDATE tb_category c
			LEFT JOIN tb_category p ON c.`parentId` = p.`id`
			SET c.`fullPath` = CONCAT(p.`fullPath`, c.`id`, '/')
		WHERE c.`id` IN(
			SELECT id FROM tmpCategory WHERE level = mCur
		);

		SET mCur = mCur + 1;

	END WHILE;


END$$

DELIMITER ;
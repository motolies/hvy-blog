DELIMITER $$

USE `blog`$$

DROP PROCEDURE IF EXISTS usp_category_fullname_update$$
create procedure usp_category_fullname_update() comment 'tb_category FullName 정리'
BEGIN

	DECLARE mCur INT;
	DECLARE mMax INT;

	SET mCur = 1;

	DROP TEMPORARY TABLE IF EXISTS tmpCategory;
	CREATE TEMPORARY TABLE tmpCategory
	(
		  Id       VARCHAR(32) NOT NULL,
		  NAME     VARCHAR(64) NOT NULL,
		  `Order`  INT(11) NOT NULL,
		  FullName VARCHAR(512) NOT NULL,
		  FullPath VARCHAR(512) NOT NULL,
		  ParentId      VARCHAR(32),
		  LEVEL		INT(11) NOT NULL
	) ENGINE = MEMORY;

	INSERT INTO tmpCategory(Id, `Name`, `Order`, ParentId, FullName, FullPath, LEVEL)
	WITH RECURSIVE cte
	AS
	(
		SELECT Id, `Name`, `Order`, ParentId, FullName, FullPath, 0 AS LEVEL
		FROM tb_category
		WHERE ParentId IS NULL
		UNION ALL
		SELECT AA.Id, AA.`Name`, AA.`Order`, AA.ParentId, AA.FullName, AA.FullPath, BB.LEVEL+1 AS LEVEL
		FROM tb_category AS AA
		   INNER JOIN cte AS BB ON AA.ParentId = BB.Id
	)
	SELECT Id, `Name`, `Order`, ParentId, FullName, FullPath, LEVEL
	FROM cte
	ORDER BY fullname, `Order`;

	SELECT MAX(LEVEL) + 1 INTO mMax FROM tmpCategory;

	WHILE mCur < mMax DO

		UPDATE tb_category c
			LEFT JOIN tb_category p ON c.`ParentId` = p.`Id`
			SET c.`FullName` = CONCAT(p.`FullName`, c.`Name`, '/')
		WHERE c.`Id` IN(
			SELECT Id FROM tmpCategory WHERE LEVEL = mCur
		);
		
		UPDATE tb_category c
			LEFT JOIN tb_category p ON c.`ParentId` = p.`Id`
			SET c.`FullPath` = CONCAT(p.`FullPath`, c.`Id`, '/')
		WHERE c.`Id` IN(
			SELECT Id FROM tmpCategory WHERE LEVEL = mCur
		);

		SET mCur = mCur + 1;

	END WHILE;


END$$

DELIMITER ;
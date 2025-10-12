-- =====================================================
-- Migration: CommonClass 필드명 변경
-- 작성일: 2025-01-12
-- 목적: CommonClass 엔티티의 필드명을 더 명확하게 변경
--       - name → code (클래스 코드)
--       - displayName → name (클래스명)
-- =====================================================


-- Step 2: 컬럼명 변경
-- name → code (클래스 코드)
ALTER TABLE `tb_common_class` CHANGE COLUMN `name` `code` VARCHAR(64) NOT NULL COMMENT '클래스 코드';

-- displayName → name (클래스명)
ALTER TABLE `tb_common_class` CHANGE COLUMN `displayName` `name` VARCHAR(128) NULL COMMENT '클래스명';

-- Step 3: 새로운 제약 조건 추가
ALTER TABLE `tb_common_class` ADD UNIQUE INDEX `uk_common_class_code` (`code`);

-- Step 4: Foreign Key 업데이트 (common_code 테이블에서 참조하는 부분)
-- 기존 foreign key 삭제
ALTER TABLE `tb_common_code` DROP FOREIGN KEY `fk_common_code_class_name`;
ALTER TABLE `tb_common_code` DROP FOREIGN KEY `fk_common_code_child_class_name`;

alter table tb_common_code
    change className classCode varchar(64) not null comment '클래스명 (복합키1)';

alter table tb_common_code
    change childClassName childClassCode varchar(64) null comment '하위클래스명 (NULL이면 leaf 노드)';

-- 새로운 foreign key 추가 (code 컬럼을 참조하도록 변경)
ALTER TABLE `tb_common_code`
    ADD CONSTRAINT `fk_common_code_class_code`
    FOREIGN KEY (`classCode`) REFERENCES `tb_common_class` (`code`)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE `tb_common_code`
    ADD CONSTRAINT `fk_common_code_child_class_code`
    FOREIGN KEY (`childClassCode`) REFERENCES `tb_common_class` (`code`)
    ON DELETE SET NULL ON UPDATE CASCADE;

-- Step 5: 데이터 검증 (옵션)
-- 마이그레이션 후 데이터 무결성 확인
SELECT
    'common_class' AS table_name,
    COUNT(*) AS total_count,
    COUNT(DISTINCT code) AS unique_code_count,
    COUNT(CASE WHEN code IS NULL OR code = '' THEN 1 END) AS null_or_empty_count
FROM `tb_common_class`
UNION ALL
SELECT
    'common_code' AS table_name,
    COUNT(*) AS total_count,
    COUNT(DISTINCT CONCAT(classCode, '.', code)) AS unique_code_count,
    COUNT(CASE WHEN classCode IS NULL OR classCode = '' THEN 1 END) AS null_or_empty_count
FROM `tb_common_code`;

-- =====================================================
-- 롤백 스크립트 (필요시 사용)
-- =====================================================
-- ALTER TABLE `tb_common_code` DROP FOREIGN KEY `fk_common_code_class_code`;
-- ALTER TABLE `tb_common_code` DROP FOREIGN KEY `fk_common_code_child_class_code`;
--
-- ALTER TABLE `tb_common_class` DROP INDEX `uk_common_class_code`;
-- ALTER TABLE `tb_common_class` CHANGE COLUMN `code` `name` VARCHAR(64) NOT NULL COMMENT '클래스명';
-- ALTER TABLE `tb_common_class` CHANGE COLUMN `name` `displayName` VARCHAR(128) NULL COMMENT '표시명';
-- ALTER TABLE `tb_common_class` ADD UNIQUE INDEX `uk_common_class_name` (`name`);
--
-- ALTER TABLE `tb_common_code`
--     ADD CONSTRAINT `fk_common_code_class_name`
--     FOREIGN KEY (`classCode`) REFERENCES `tb_common_class` (`name`)
--     ON DELETE RESTRICT ON UPDATE CASCADE;
--
-- ALTER TABLE `tb_common_code`
--     ADD CONSTRAINT `fk_common_code_child_class_name`
--     FOREIGN KEY (`childClassCode`) REFERENCES `tb_common_class` (`name`)
--     ON DELETE SET NULL ON UPDATE CASCADE;

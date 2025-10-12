-- =====================================================
-- Migration: Surrogate Key 도입 (tb_common_class, tb_common_code)
-- 작성일: 2025-01-14
-- 목적: String PK를 Long ID로 변경하여 코드 수정 용이성 향상
--       - code 필드를 UNIQUE 제약조건으로만 관리
--       - ID 기반 FK 관계로 변경
--       - API 호환성 유지 (code 기반 조회 지원)
-- =====================================================

-- =====================================================
-- Phase 1: 새 ID 컬럼 추가 (NOT NULL 아님)
-- =====================================================

-- tb_common_class에 id 컬럼 추가
ALTER TABLE tb_common_class
    ADD COLUMN id BIGINT NULL FIRST;

-- tb_common_code에 id와 classId 컬럼 추가
ALTER TABLE tb_common_code
    ADD COLUMN id BIGINT NULL FIRST,
    ADD COLUMN classId BIGINT NULL AFTER id,
    ADD COLUMN childClassId BIGINT NULL;

-- =====================================================
-- Phase 2: 기존 데이터에 ID 할당
-- =====================================================

-- CommonClass에 순차적 ID 할당
SET @row_number = 0;
UPDATE tb_common_class
SET id = (@row_number:=@row_number+1)
ORDER BY code;

-- CommonCode에 순차적 ID 할당
SET @row_number = 0;
UPDATE tb_common_code
SET id = (@row_number:=@row_number+1)
ORDER BY classCode, code;

-- classId 매핑 (classCode -> tb_common_class.id)
UPDATE tb_common_code cc
INNER JOIN tb_common_class cls ON cc.classCode = cls.code
SET cc.classId = cls.id;

-- childClassId 매핑 (childClassCode -> tb_common_class.id)
UPDATE tb_common_code cc
INNER JOIN tb_common_class cls ON cc.childClassCode = cls.code
SET cc.childClassId = cls.id
WHERE cc.childClassCode IS NOT NULL;

-- =====================================================
-- Phase 3: 데이터 검증
-- =====================================================

-- 모든 데이터에 ID가 할당되었는지 확인
SELECT 'Validation: IDs assigned' AS step,
       'common_class' AS table_name,
       COUNT(*) AS total,
       COUNT(id) AS with_id,
       COUNT(*) - COUNT(id) AS missing_id
FROM tb_common_class
UNION ALL
SELECT 'Validation: IDs assigned' AS step,
       'common_code' AS table_name,
       COUNT(*) AS total,
       COUNT(id) AS with_id,
       COUNT(*) - COUNT(id) AS missing_id
FROM tb_common_code;

-- classId 매핑 검증
SELECT 'Validation: FK mapping' AS step,
       COUNT(*) AS unmapped_codes
FROM tb_common_code
WHERE classId IS NULL;

-- =====================================================
-- Phase 4: 기존 FK 제약조건 제거
-- =====================================================

ALTER TABLE tb_common_code
    DROP FOREIGN KEY fk_common_code_class_code;

ALTER TABLE tb_common_code
    DROP FOREIGN KEY fk_common_code_child_class_code;

-- =====================================================
-- Phase 5: PK 변경
-- =====================================================

-- tb_common_class PK 변경
ALTER TABLE tb_common_class
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (id),
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;

-- code 컬럼 UNIQUE 제약조건 추가 (기존 제약이 있으면 유지)
ALTER TABLE tb_common_class
    ADD UNIQUE INDEX uk_common_class_code (code);

-- tb_common_code PK 변경
ALTER TABLE tb_common_code
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (id),
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;

-- =====================================================
-- Phase 6: 새 FK 제약조건 및 인덱스 추가
-- =====================================================

-- classId NOT NULL로 변경 및 FK 추가
ALTER TABLE tb_common_code
    MODIFY COLUMN classId BIGINT NOT NULL,
    ADD CONSTRAINT fk_common_code_class_id
        FOREIGN KEY (classId) REFERENCES tb_common_class (id)
        ON DELETE RESTRICT ON UPDATE CASCADE;

-- childClassId FK 추가
ALTER TABLE tb_common_code
    ADD CONSTRAINT fk_common_code_child_class_id
        FOREIGN KEY (childClassId) REFERENCES tb_common_class (id)
        ON DELETE SET NULL ON UPDATE CASCADE;

-- (classId, code) 복합 UNIQUE 제약조건 추가
ALTER TABLE tb_common_code
    ADD UNIQUE INDEX uk_common_code_class_code (classId, code);

-- classId 인덱스 추가 (FK 성능 향상)
ALTER TABLE tb_common_code
    ADD INDEX idx_common_code_class_id (classId);

-- childClassId 인덱스 추가 (FK 성능 향상)
ALTER TABLE tb_common_code
    ADD INDEX idx_common_code_child_class_id (childClassId);

-- =====================================================
-- Phase 7: 최종 데이터 검증
-- =====================================================

-- FK 무결성 확인
SELECT 'Final Validation: Orphaned codes' AS step, COUNT(*) as orphaned_codes
FROM tb_common_code
WHERE classId NOT IN (SELECT id FROM tb_common_class);

-- 중복 code 확인
SELECT 'Final Validation: Duplicate class codes' AS step, code, COUNT(*) as count
FROM tb_common_class
GROUP BY code
HAVING COUNT(*) > 1;

-- 중복 (classId, code) 확인
SELECT 'Final Validation: Duplicate codes' AS step, classId, code, COUNT(*) as count
FROM tb_common_code
GROUP BY classId, code
HAVING COUNT(*) > 1;

-- =====================================================
-- 완료 메시지
-- =====================================================
SELECT 'Migration completed successfully' AS status,
       NOW() AS completed_at;

-- =====================================================
-- 참고: 기존 컬럼 정리 (선택사항)
-- =====================================================
-- classCode, childClassCode 컬럼은 호환성을 위해 유지합니다.
-- 완전히 제거하려면 아래 주석을 해제하세요:
--
-- ALTER TABLE tb_common_code DROP COLUMN classCode;
-- ALTER TABLE tb_common_code DROP COLUMN childClassCode;

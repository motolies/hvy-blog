-- =====================================================
-- ROLLBACK: Surrogate Key 마이그레이션 롤백
-- 작성일: 2025-01-14
-- 목적: V20250114_01 마이그레이션을 롤백하여 이전 상태로 복원
-- 주의: 이 스크립트는 긴급 롤백 시에만 사용하세요!
-- =====================================================

-- =====================================================
-- Phase 1: 새로 추가된 FK 제약조건 제거
-- =====================================================

ALTER TABLE tb_common_code
    DROP FOREIGN KEY fk_common_code_class_id;

ALTER TABLE tb_common_code
    DROP FOREIGN KEY fk_common_code_child_class_id;

-- =====================================================
-- Phase 2: 새로 추가된 인덱스 제거
-- =====================================================

ALTER TABLE tb_common_code
    DROP INDEX uk_common_code_class_code;

ALTER TABLE tb_common_code
    DROP INDEX idx_common_code_class_id;

ALTER TABLE tb_common_code
    DROP INDEX idx_common_code_child_class_id;

ALTER TABLE tb_common_class
    DROP INDEX uk_common_class_code;

-- =====================================================
-- Phase 3: PK 복원
-- =====================================================

-- tb_common_class PK 복원
ALTER TABLE tb_common_class
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (code);

-- tb_common_code PK 복원
ALTER TABLE tb_common_code
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (classCode, code);

-- =====================================================
-- Phase 4: 기존 FK 제약조건 복원
-- =====================================================

ALTER TABLE tb_common_code
    ADD CONSTRAINT fk_common_code_class_code
        FOREIGN KEY (classCode) REFERENCES tb_common_class (code)
        ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE tb_common_code
    ADD CONSTRAINT fk_common_code_child_class_code
        FOREIGN KEY (childClassCode) REFERENCES tb_common_class (code)
        ON DELETE SET NULL ON UPDATE CASCADE;

-- =====================================================
-- Phase 5: 새로 추가된 컬럼 제거
-- =====================================================

ALTER TABLE tb_common_class
    DROP COLUMN id;

ALTER TABLE tb_common_code
    DROP COLUMN id,
    DROP COLUMN classId,
    DROP COLUMN childClassId;

-- =====================================================
-- Phase 6: 데이터 검증
-- =====================================================

-- PK 복원 확인
SELECT 'Validation: PKs restored' AS step,
       'common_class' AS table_name,
       COUNT(*) AS total_rows
FROM tb_common_class
UNION ALL
SELECT 'Validation: PKs restored' AS step,
       'common_code' AS table_name,
       COUNT(*) AS total_rows
FROM tb_common_code;

-- FK 관계 확인
SELECT 'Validation: FK integrity' AS step,
       COUNT(*) AS orphaned_codes
FROM tb_common_code cc
LEFT JOIN tb_common_class cls ON cc.classCode = cls.code
WHERE cls.code IS NULL;

-- =====================================================
-- 완료 메시지
-- =====================================================
SELECT 'Rollback completed successfully' AS status,
       'Schema restored to previous state' AS message,
       NOW() AS completed_at;

-- =====================================================
-- 주의사항
-- =====================================================
-- 1. 이 롤백 스크립트는 마이그레이션 직후에만 안전합니다.
-- 2. 마이그레이션 후 데이터가 변경되었다면 롤백이 실패할 수 있습니다.
-- 3. 롤백 전 반드시 데이터베이스 백업을 확인하세요.
-- 4. 프로덕션 환경에서는 DBA와 상의 후 실행하세요.

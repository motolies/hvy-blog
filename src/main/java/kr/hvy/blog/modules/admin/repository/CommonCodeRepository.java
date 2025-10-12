package kr.hvy.blog.modules.admin.repository;

import kr.hvy.blog.modules.admin.domain.entity.CommonCode;
import kr.hvy.blog.modules.admin.domain.entity.CommonCodeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CommonCode Repository
 * 공통코드에 대한 데이터 접근 인터페이스
 * PK: Long (Surrogate Key)
 */
@Repository
public interface CommonCodeRepository extends JpaRepository<CommonCode, Long> {

  /**
   * 클래스 코드로 활성화된 코드들 조회 (정렬순서, 코드순)
   */
  List<CommonCode> findByCommonClass_CodeAndIsActiveTrueOrderBySortAscCodeAsc(String classCode);

  /**
   * 클래스 코드와 코드로 활성화된 코드 조회
   */
  Optional<CommonCode> findByCommonClass_CodeAndCodeAndIsActiveTrue(String classCode, String code);

  /**
   * 클래스 코드로 모든 코드 조회 (비활성화 포함)
   */
  List<CommonCode> findByCommonClass_CodeOrderBySortAscCodeAsc(String classCode);

  /**
   * 자식을 가진 코드들만 조회 (childClass가 있는 것들)
   */
  List<CommonCode> findByCommonClass_CodeAndChildClassIsNotNullAndIsActiveTrueOrderBySortAsc(String classCode);

  /**
   * 자식을 가지지 않는 코드들만 조회 (leaf 노드: childClass가 null)
   */
  List<CommonCode> findByCommonClass_CodeAndChildClassIsNullAndIsActiveTrueOrderBySortAsc(String classCode);

  /**
   * 특정 자식 클래스를 참조하는 코드들 조회
   */
  List<CommonCode> findByChildClass_CodeAndIsActiveTrueOrderBySortAsc(String childClassCode);

  /**
   * 코드명으로 검색 (like 검색)
   */
  @Query("SELECT c FROM CommonCode c WHERE c.commonClass.code = :classCode AND c.isActive = true AND " +
      "c.name LIKE %:searchTerm% ORDER BY c.sort ASC, c.code ASC")
  List<CommonCode> searchByNameInClass(@Param("classCode") String classCode, @Param("searchTerm") String searchTerm);

  /**
   * 전체 클래스에서 코드명으로 검색
   */
  @Query("SELECT c FROM CommonCode c WHERE c.isActive = true AND " +
      "c.name LIKE %:searchTerm% ORDER BY c.commonClass.code ASC, c.sort ASC, c.code ASC")
  List<CommonCode> searchByNameAcrossAllClasses(@Param("searchTerm") String searchTerm);

  /**
   * 특정 속성값을 가진 코드들 조회
   */
  @Query("SELECT c FROM CommonCode c WHERE c.commonClass.code = :classCode AND c.isActive = true AND " +
      "(c.attribute1Value = :attributeValue OR " +
      "c.attribute2Value = :attributeValue OR " +
      "c.attribute3Value = :attributeValue OR " +
      "c.attribute4Value = :attributeValue OR " +
      "c.attribute5Value = :attributeValue)")
  List<CommonCode> findByAttributeValue(@Param("classCode") String classCode, @Param("attributeValue") String attributeValue);

  /**
   * 클래스 내에서 코드 중복 확인
   */
  boolean existsByCommonClass_CodeAndCode(String classCode, String code);

  /**
   * 클래스 내에서 활성화된 코드 중복 확인
   */
  boolean existsByCommonClass_CodeAndCodeAndIsActiveTrue(String classCode, String code);

  /**
   * 특정 클래스의 전체 코드 개수
   */
  long countByCommonClass_CodeAndIsActiveTrue(String classCode);

  /**
   * 특정 클래스의 최대 정렬 순서 조회
   */
  @Query("SELECT COALESCE(MAX(c.sort), 0) FROM CommonCode c WHERE c.commonClass.code = :classCode")
  Integer findMaxSortByClassCode(@Param("classCode") String classCode);

  /**
   * 계층 구조 조회: 특정 코드의 하위 코드들
   */
  @Query("SELECT c FROM CommonCode c WHERE c.commonClass.id = " +
      "(SELECT parent.childClass.id FROM CommonCode parent WHERE parent.commonClass.code = :parentClassCode AND parent.code = :parentCode) " +
      "AND c.isActive = true ORDER BY c.sort ASC, c.code ASC")
  List<CommonCode> findChildCodes(@Param("parentClassCode") String parentClassCode, @Param("parentCode") String parentCode);

  /**
   * 클래스별 코드 통계
   */
  @Query("SELECT c.commonClass.code, COUNT(c) FROM CommonCode c WHERE c.isActive = true GROUP BY c.commonClass.code")
  List<Object[]> getCodeCountByClass();
}

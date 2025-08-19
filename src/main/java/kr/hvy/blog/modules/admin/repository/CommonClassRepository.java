package kr.hvy.blog.modules.admin.repository;

import kr.hvy.blog.modules.admin.domain.entity.CommonClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CommonClass Repository
 * 공통코드 클래스에 대한 데이터 접근 인터페이스
 */
@Repository
public interface CommonClassRepository extends JpaRepository<CommonClass, String> {

  /**
   * 활성화된 클래스만 조회
   */
  List<CommonClass> findByIsActiveTrue();

  /**
   * 이름으로 활성화된 클래스 조회
   */
  Optional<CommonClass> findByNameAndIsActiveTrue(String name);

  /**
   * 표시명으로 검색
   */
  List<CommonClass> findByDisplayNameContainingAndIsActiveTrue(String displayName);

  /**
   * 활성화된 클래스들을 이름순으로 조회
   */
  @Query("SELECT c FROM CommonClass c WHERE c.isActive = true ORDER BY c.name ASC")
  List<CommonClass> findActiveClassesOrderByName();

  /**
   * 특정 속성명을 가진 클래스들 조회
   */
  @Query("SELECT c FROM CommonClass c WHERE c.isActive = true AND " +
      "(c.attribute1Name = :attributeName OR " +
      "c.attribute2Name = :attributeName OR " +
      "c.attribute3Name = :attributeName OR " +
      "c.attribute4Name = :attributeName OR " +
      "c.attribute5Name = :attributeName)")
  List<CommonClass> findByAttributeName(@Param("attributeName") String attributeName);

  /**
   * 이름 존재 여부 확인
   */
  boolean existsByName(String name);

  /**
   * 이름 존재 여부 확인 (활성화된 것만)
   */
  boolean existsByNameAndIsActiveTrue(String name);
}

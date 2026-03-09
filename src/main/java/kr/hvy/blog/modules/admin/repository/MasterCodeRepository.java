package kr.hvy.blog.modules.admin.repository;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 마스터코드 리포지토리
 */
@Repository
public interface MasterCodeRepository extends JpaRepository<MasterCode, Long> {

  /**
   * 활성화된 루트 노드 목록 조회
   */
  List<MasterCode> findByParentIsNullAndIsActiveTrueOrderBySortAscCodeAsc();

  /**
   * 특정 부모의 활성화된 자식 노드 조회
   */
  List<MasterCode> findByParentIdAndIsActiveTrueOrderBySortAscCodeAsc(Long parentId);

  /**
   * 루트 코드값으로 루트 노드 조회
   */
  Optional<MasterCode> findByCodeAndParentIsNullAndIsActiveTrue(String code);

  /**
   * 전체 활성화된 노드를 트리 빌딩 순서로 조회
   */
  List<MasterCode> findByIsActiveTrueOrderByDepthAscSortAscCodeAsc();

  /**
   * Materialized Path를 이용한 서브트리 조회
   */
  @Query("SELECT m FROM MasterCode m WHERE m.path LIKE :pathPrefix% AND m.isActive = true ORDER BY m.depth ASC, m.sort ASC, m.code ASC")
  List<MasterCode> findSubtree(@Param("pathPrefix") String pathPrefix);

  /**
   * 이름 또는 코드로 검색
   */
  @Query("SELECT m FROM MasterCode m WHERE m.isActive = true AND (LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(m.code) LIKE LOWER(CONCAT('%', :keyword, '%'))) ORDER BY m.depth ASC, m.sort ASC")
  List<MasterCode> searchByNameOrCode(@Param("keyword") String keyword);

  /**
   * 특정 부모 하위의 최대 정렬순서 조회
   */
  @Query("SELECT COALESCE(MAX(m.sort), 0) FROM MasterCode m WHERE m.parent.id = :parentId")
  Integer findMaxSortByParentId(@Param("parentId") Long parentId);

  /**
   * 루트 노드의 최대 정렬순서 조회
   */
  @Query("SELECT COALESCE(MAX(m.sort), 0) FROM MasterCode m WHERE m.parent IS NULL")
  Integer findMaxSortForRoot();

  /**
   * 부모+코드 중복 확인 (루트)
   */
  boolean existsByCodeAndParentIsNull(String code);

  /**
   * 부모+코드 중복 확인 (자식)
   */
  boolean existsByParentIdAndCode(Long parentId, String code);

  /**
   * 특정 부모의 자식 수 조회
   */
  long countByParentIdAndIsActiveTrue(Long parentId);
}

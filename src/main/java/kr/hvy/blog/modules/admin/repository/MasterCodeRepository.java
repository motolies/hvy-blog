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
public interface MasterCodeRepository extends JpaRepository<MasterCode, String> {

  /**
   * 활성화된 루트 노드 목록 조회
   */
  List<MasterCode> findByParentIsNullAndIsActiveTrueOrderBySortAscCodeAsc();

  /**
   * 특정 부모의 활성화된 자식 노드 조회
   */
  List<MasterCode> findByParentIdAndIsActiveTrueOrderBySortAscCodeAsc(String parentId);

  /**
   * 특정 부모의 자식 노드 <b>전체</b> 조회 (비활성 포함).
   * <p>
   * ⚠️ 정렬 재부여에는 위의 {@code ...AndIsActiveTrue...} 를 쓸 수 없다. 관리 화면조차
   * {@link #findSubtree(String)} 가 비활성 노드를 걸러 <b>애초에 보지 못하므로</b>, 활성만 renumber 하면
   * 화면에 없던 비활성 형제의 sort 가 1..n 블록과 겹쳐 {@code @OrderBy("sort ASC, code ASC")} 의
   * code 폴백으로 순서가 무너진다. 비활성까지 함께 정규화해야 나중에 다시 켰을 때 충돌이 없다.
   */
  List<MasterCode> findByParentIdOrderBySortAscCodeAsc(String parentId);

  /**
   * 루트 코드값으로 루트 노드 조회
   */
  Optional<MasterCode> findByCodeAndParentIsNullAndIsActiveTrue(String code);

  /**
   * 전체 활성화된 노드를 트리 빌딩 순서로 조회
   */
  List<MasterCode> findByIsActiveTrueOrderByDepthAscSortAscCodeAsc();

  /**
   * Materialized Path를 이용한 서브트리 조회 (자기 자신 포함).
   * <p>
   * 경계를 {@code /} 로 못박는다. 단순히 {@code LIKE prefix || '%'} 로 하면 {@code '/6'} 이
   * {@code /60}, {@code /61/...} 처럼 <b>id 가 그 숫자로 시작하는 다른 루트를 삼킨다</b>.
   * id 가 고정 13자 TSID 로 바뀌어 실제 충돌은 사라졌지만, 옛 형식 path 가 섞여 들어와도
   * 안전하도록 의도를 쿼리에 남긴다.
   * <p>
   * ⚠️ JPQL {@code CONCAT} 은 PostgreSQL 방언에서 {@code concat()} 함수가 아니라 {@code ||}
   * 연산자로 렌더링된다. 따라서 {@code pathPrefix} 가 null 이면 조건 전체가 UNKNOWN 이 되어
   * <b>0건</b>이 나온다(빈 결과가 아니라 조용한 실패). path 는 NOT NULL 로 그 경로를 막았다.
   */
  @Query("SELECT m FROM MasterCode m WHERE (m.path = :pathPrefix OR m.path LIKE CONCAT(:pathPrefix, '/%')) AND m.isActive = true ORDER BY m.depth ASC, m.sort ASC, m.code ASC")
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
  Integer findMaxSortByParentId(@Param("parentId") String parentId);

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
  boolean existsByParentIdAndCode(String parentId, String code);

  /**
   * 특정 부모의 자식 수 조회
   */
  long countByParentIdAndIsActiveTrue(String parentId);
}

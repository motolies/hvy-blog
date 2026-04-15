package kr.hvy.blog.modules.admin.infrastructure.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeResponse;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import kr.hvy.common.infrastructure.redis.impl.masterdata.query.MasterCodeLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * blog-back 전용 JPA 기반 {@link MasterCodeLoader} 구현.
 * <p>
 * L1/L2 캐시 미스 시 {@link MasterCodeRepository} 로 DB 를 직접 조회한다.
 * 소유자 애플리케이션에서만 사용되며, 소비 앱은 {@code RestClientMasterCodeLoader} 를 쓴다.
 */
@RequiredArgsConstructor
public class JpaMasterCodeLoader implements MasterCodeLoader {

  private final MasterCodeRepository masterCodeRepository;
  private final MasterCodeDtoMapper masterCodeDtoMapper;

  @Override
  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> loadFullTree() {
    List<MasterCode> all = masterCodeRepository.findByIsActiveTrueOrderByDepthAscSortAscCodeAsc();
    return buildTree(all);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> loadSubTree(String rootCode) {
    MasterCode root = findRootByCode(rootCode);
    List<MasterCode> subtreeNodes = masterCodeRepository.findSubtree(root.getPath());
    return buildTree(subtreeNodes);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MasterCodeResponse> loadChildren(String rootCode) {
    MasterCode root = findRootByCode(rootCode);
    List<MasterCode> children =
        masterCodeRepository.findByParentIdAndIsActiveTrueOrderBySortAscCodeAsc(root.getId());
    return masterCodeDtoMapper.toResponseList(children);
  }

  // ========== 내부 헬퍼 ==========

  private MasterCode findRootByCode(String rootCode) {
    return masterCodeRepository.findByCodeAndParentIsNullAndIsActiveTrue(rootCode)
        .orElseThrow(() -> new DataNotFoundException("루트 코드를 찾을 수 없습니다: " + rootCode));
  }

  /**
   * depth ASC 순서로 정렬된 엔티티 목록을 메모리에서 트리로 재구성.
   */
  private List<MasterCodeTreeResponse> buildTree(List<MasterCode> nodes) {
    Map<Long, MasterCodeTreeResponse> map = new LinkedHashMap<>();
    List<MasterCodeTreeResponse> roots = new ArrayList<>();

    for (MasterCode entity : nodes) {
      MasterCodeTreeResponse node = MasterCodeTreeResponse.builder()
          .id(entity.getId())
          .code(entity.getCode())
          .name(entity.getName())
          .description(entity.getDescription())
          .attributes(entity.getAttributes())
          .attributeSchema(entity.getAttributeSchema())
          .depth(entity.getDepth())
          .sort(entity.getSort())
          .isActive(entity.getIsActive())
          .children(new ArrayList<>())
          .build();

      map.put(entity.getId(), node);

      if (entity.getParent() == null) {
        roots.add(node);
      } else {
        MasterCodeTreeResponse parentNode = map.get(entity.getParent().getId());
        if (parentNode != null) {
          parentNode.getChildren().add(node);
        }
      }
    }

    return roots;
  }
}

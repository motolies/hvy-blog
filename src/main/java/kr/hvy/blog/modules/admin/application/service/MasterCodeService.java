package kr.hvy.blog.modules.admin.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeCreate;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeMoveRequest;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeTreeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeUpdate;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/**
 * 마스터코드 서비스
 * CRUD + 트리 빌딩 + 캐시 연동
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class MasterCodeService {

  @PersistenceContext
  private EntityManager entityManager;

  private final MasterCodeRepository masterCodeRepository;
  private final MasterCodeDtoMapper masterCodeDtoMapper;
  private final MasterCodeCacheService cacheService;

  // ========== 트리 조회 ==========

  /**
   * 전체 트리 빌딩
   * 모든 활성화된 노드를 한번에 조회 후 메모리에서 트리 구성
   */
  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> getFullTree() {
    List<MasterCodeTreeResponse> cached = cacheService.getFullTree();
    if (!CollectionUtils.isEmpty(cached)) {
      return cached;
    }

    List<MasterCode> all = masterCodeRepository.findByIsActiveTrueOrderByDepthAscSortAscCodeAsc();
    List<MasterCodeTreeResponse> tree = buildTree(all);

    cacheService.putFullTree(tree);
    return tree;
  }

  /**
   * 루트 코드별 서브트리 조회
   */
  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> getSubTree(String rootCode) {
    List<MasterCodeTreeResponse> cached = cacheService.getSubTree(rootCode);
    if (cached != null) {
      return cached;
    }

    MasterCode root = findRootByCode(rootCode);
    List<MasterCode> subtreeNodes = masterCodeRepository.findSubtree(root.getPath());
    List<MasterCodeTreeResponse> tree = buildTree(subtreeNodes);

    cacheService.putSubTree(rootCode, tree);
    return tree;
  }

  /**
   * 루트의 직계 자식 목록 조회 (플랫)
   * Jira 등 외부 모듈에서 사용
   */
  @Transactional(readOnly = true)
  public List<MasterCodeResponse> getChildrenByRootCode(String rootCode) {
    List<MasterCodeResponse> cached = cacheService.getChildren(rootCode);
    if (cached != null) {
      return cached;
    }

    MasterCode root = findRootByCode(rootCode);
    List<MasterCode> children = masterCodeRepository.findByParentIdAndIsActiveTrueOrderBySortAscCodeAsc(root.getId());
    List<MasterCodeResponse> result = masterCodeDtoMapper.toResponseList(children);

    cacheService.putChildren(rootCode, result);
    return result;
  }

  /**
   * 루트 노드 목록 조회
   */
  @Transactional(readOnly = true)
  public List<MasterCodeResponse> getGroups() {
    List<MasterCode> roots = masterCodeRepository.findByParentIsNullAndIsActiveTrueOrderBySortAscCodeAsc();
    return masterCodeDtoMapper.toResponseList(roots);
  }

  /**
   * 루트별 플랫 목록 조회 (select box용)
   */
  @Transactional(readOnly = true)
  public List<MasterCodeResponse> getFlatCodes(String rootCode) {
    MasterCode root = findRootByCode(rootCode);
    List<MasterCode> subtreeNodes = masterCodeRepository.findSubtree(root.getPath());

    // 루트 자신은 제외하고 하위만 반환
    return subtreeNodes.stream()
        .filter(node -> !node.getId().equals(root.getId()))
        .map(masterCodeDtoMapper::toResponse)
        .toList();
  }

  /**
   * 단일 노드 조회
   */
  @Transactional(readOnly = true)
  public MasterCodeResponse getNode(Long id) {
    MasterCode entity = findById(id);
    return masterCodeDtoMapper.toResponse(entity);
  }

  // ========== CRUD ==========

  /**
   * 노드 생성
   */
  public MasterCodeResponse createNode(MasterCodeCreate createDto) {
    MasterCode parent = null;
    int depth = 0;

    if (createDto.getParentId() != null) {
      parent = findById(createDto.getParentId());
      depth = parent.getDepth() + 1;

      // 중복 코드 검사 (같은 부모 하위)
      if (masterCodeRepository.existsByParentIdAndCode(parent.getId(), createDto.getCode())) {
        throw new IllegalArgumentException(
            String.format("이미 존재하는 코드입니다: %s (부모 ID: %d)", createDto.getCode(), parent.getId()));
      }
    } else {
      // 루트 중복 코드 검사
      if (masterCodeRepository.existsByCodeAndParentIsNull(createDto.getCode())) {
        throw new IllegalArgumentException("이미 존재하는 루트 코드입니다: " + createDto.getCode());
      }
    }

    // 정렬순서 자동 설정
    Integer sort = createDto.getSort();
    if (ObjectUtils.isEmpty(sort) || sort == 0) {
      sort = (parent != null)
          ? masterCodeRepository.findMaxSortByParentId(parent.getId()) + 1
          : masterCodeRepository.findMaxSortForRoot() + 1;
    }

    MasterCode entity = MasterCode.builder()
        .parent(parent)
        .depth(depth)
        .code(createDto.getCode())
        .name(createDto.getName())
        .description(createDto.getDescription())
        .attributes(createDto.getAttributes() != null ? createDto.getAttributes() : Map.of())
        .attributeSchema(createDto.getAttributeSchema() != null ? createDto.getAttributeSchema() : List.of())
        .sort(sort)
        .isActive(createDto.getIsActive() != null ? createDto.getIsActive() : true)
        .build();

    MasterCode saved = masterCodeRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(saved);

    // path 설정 (ID가 확정된 후)
    saved.recalculateTreeFields();
    masterCodeRepository.save(saved);

    log.info("MasterCode 생성: code={}, depth={}, parentId={}", saved.getCode(), saved.getDepth(),
        parent != null ? parent.getId() : "ROOT");

    evictCacheForNode(saved);
    return masterCodeDtoMapper.toResponse(saved);
  }

  /**
   * 노드 수정
   */
  public MasterCodeResponse updateNode(Long id, MasterCodeUpdate updateDto) {
    MasterCode entity = findById(id);

    // code 변경 시 중복 검증
    if (ObjectUtils.isNotEmpty(updateDto.getCode()) && !updateDto.getCode().trim().isEmpty()
        && !entity.getCode().equals(updateDto.getCode())) {
      if (entity.isRoot()) {
        if (masterCodeRepository.existsByCodeAndParentIsNull(updateDto.getCode())) {
          throw new IllegalArgumentException("이미 존재하는 루트 코드입니다: " + updateDto.getCode());
        }
      } else {
        if (masterCodeRepository.existsByParentIdAndCode(entity.getParent().getId(), updateDto.getCode())) {
          throw new IllegalArgumentException(
              String.format("이미 존재하는 코드입니다: %s (부모 ID: %d)", updateDto.getCode(), entity.getParent().getId()));
        }
      }
    }

    entity.update(
        updateDto.getCode(), updateDto.getName(), updateDto.getDescription(),
        updateDto.getAttributes(), updateDto.getAttributeSchema(),
        updateDto.getSort(), updateDto.getIsActive()
    );

    MasterCode saved = masterCodeRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(saved);

    log.info("MasterCode 수정: id={}, code={}", saved.getId(), saved.getCode());

    evictCacheForNode(saved);
    return masterCodeDtoMapper.toResponse(saved);
  }

  /**
   * 노드 삭제 (자식 존재 시 거부)
   */
  public DeleteResponse<Long> deleteNode(Long id) {
    MasterCode entity = findById(id);

    long childCount = masterCodeRepository.countByParentIdAndIsActiveTrue(id);
    if (childCount > 0) {
      throw new IllegalArgumentException("하위 노드가 존재하여 삭제할 수 없습니다. 하위 노드 수: " + childCount);
    }

    Long deletedId = entity.getId();
    masterCodeRepository.delete(entity);

    log.info("MasterCode 삭제: id={}, code={}", deletedId, entity.getCode());

    evictCacheForNode(entity);

    return DeleteResponse.<Long>builder()
        .id(deletedId)
        .build();
  }

  /**
   * 노드 이동 (부모 변경)
   */
  public MasterCodeResponse moveNode(Long id, MasterCodeMoveRequest moveRequest) {
    MasterCode entity = findById(id);

    MasterCode newParent = null;
    if (moveRequest.getNewParentId() != null) {
      newParent = findById(moveRequest.getNewParentId());

      // 순환 참조 방지: 자기 자신이나 자기 하위로 이동 불가
      if (newParent.getId().equals(id)) {
        throw new IllegalArgumentException("자기 자신으로 이동할 수 없습니다");
      }
      if (newParent.getPath() != null && newParent.getPath().contains("/" + id + "/")) {
        throw new IllegalArgumentException("자기 하위 노드로 이동할 수 없습니다");
      }
    }

    // 기존 루트 코드 (캐시 무효화용)
    String oldRootCode = findRootCode(entity);

    entity.setParent(newParent);
    entity.recalculateTreeFields();

    MasterCode saved = masterCodeRepository.save(entity);
    entityManager.flush();

    // 하위 노드들의 depth/path 재계산
    recalculateChildrenTreeFields(saved);

    entityManager.refresh(saved);

    log.info("MasterCode 이동: id={}, code={}, newParentId={}", saved.getId(), saved.getCode(),
        newParent != null ? newParent.getId() : "ROOT");

    // 기존/신규 루트 코드 모두 캐시 무효화
    String newRootCode = findRootCode(saved);
    cacheService.evictByRootCode(oldRootCode);
    if (!oldRootCode.equals(newRootCode)) {
      cacheService.evictByRootCode(newRootCode);
    }

    return masterCodeDtoMapper.toResponse(saved);
  }

  /**
   * 이름/코드 검색
   */
  @Transactional(readOnly = true)
  public List<MasterCodeResponse> searchNodes(String keyword) {
    List<MasterCode> results = masterCodeRepository.searchByNameOrCode(keyword);
    return masterCodeDtoMapper.toResponseList(results);
  }

  // ========== 내부 헬퍼 ==========

  private MasterCode findById(Long id) {
    return masterCodeRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("마스터코드를 찾을 수 없습니다: ID " + id));
  }

  private MasterCode findRootByCode(String rootCode) {
    return masterCodeRepository.findByCodeAndParentIsNullAndIsActiveTrue(rootCode)
        .orElseThrow(() -> new DataNotFoundException("루트 코드를 찾을 수 없습니다: " + rootCode));
  }

  /**
   * 트리 빌딩 (depth ASC 순서로 정렬된 노드 목록 -> 트리)
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

  /**
   * 노드의 루트 코드를 찾아 반환
   */
  private String findRootCode(MasterCode node) {
    MasterCode current = node;
    while (current.getParent() != null) {
      current = current.getParent();
    }
    return current.getCode();
  }

  /**
   * 하위 노드들의 depth/path 재귀 재계산
   */
  private void recalculateChildrenTreeFields(MasterCode parent) {
    List<MasterCode> children = masterCodeRepository.findByParentIdAndIsActiveTrueOrderBySortAscCodeAsc(parent.getId());
    for (MasterCode child : children) {
      child.setDepth(parent.getDepth() + 1);
      child.setPath(parent.getPath() + "/" + child.getId());
      masterCodeRepository.save(child);
      recalculateChildrenTreeFields(child);
    }
  }

  /**
   * 노드 변경 시 관련 캐시 무효화
   */
  private void evictCacheForNode(MasterCode node) {
    String rootCode = findRootCode(node);
    cacheService.evictByRootCode(rootCode);
  }
}

package kr.hvy.blog.modules.admin.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeCreate;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeMoveRequest;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeUpdate;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.infrastructure.redis.impl.masterdata.cache.MasterCodeCacheService;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeResponse;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import kr.hvy.common.infrastructure.redis.impl.masterdata.query.MasterCodeQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마스터코드 서비스.
 * <p>
 * <b>읽기 경로</b>는 hvy-common 의 {@link MasterCodeQuery} Facade 로 위임(L1 → L2 → JpaMasterCodeLoader → DB).
 * <b>쓰기 경로</b>는 본 서비스에서 CRUD 후 {@link MasterCodeCacheService#evictByRootCode(String)} 로 캐시 무효화를
 * 직접 호출한다. 무효화 이벤트는 TwoTierCache 내부에서 Redis Pub/Sub 으로 자동 전파된다.
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
  private final MasterCodeQuery masterCodeQuery;

  // ========== 트리 조회 (캐시 경유) ==========

  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> getFullTree() {
    return masterCodeQuery.getFullTree();
  }

  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> getSubTree(String rootCode) {
    return masterCodeQuery.getSubTree(rootCode);
  }

  /**
   * 루트의 직계 자식 목록 조회 (Jira 등 외부 모듈에서 사용).
   */
  @Transactional(readOnly = true)
  public List<MasterCodeResponse> getChildrenByRootCode(String rootCode) {
    return masterCodeQuery.getChildren(rootCode);
  }

  // ========== 비캐시 조회 ==========

  @Transactional(readOnly = true)
  public List<MasterCodeResponse> getGroups() {
    List<MasterCode> roots = masterCodeRepository.findByParentIsNullAndIsActiveTrueOrderBySortAscCodeAsc();
    return masterCodeDtoMapper.toResponseList(roots);
  }

  @Transactional(readOnly = true)
  public List<MasterCodeResponse> getFlatCodes(String rootCode) {
    MasterCode root = findRootByCode(rootCode);
    List<MasterCode> subtreeNodes = masterCodeRepository.findSubtree(root.getPath());
    return subtreeNodes.stream()
        .filter(node -> !node.getId().equals(root.getId()))
        .map(masterCodeDtoMapper::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public MasterCodeResponse getNode(Long id) {
    MasterCode entity = findById(id);
    return masterCodeDtoMapper.toResponse(entity);
  }

  // ========== CRUD ==========

  public MasterCodeResponse createNode(MasterCodeCreate createDto) {
    MasterCode parent = null;
    int depth = 0;

    if (createDto.getParentId() != null) {
      parent = findById(createDto.getParentId());
      depth = parent.getDepth() + 1;

      if (masterCodeRepository.existsByParentIdAndCode(parent.getId(), createDto.getCode())) {
        throw new IllegalArgumentException(
            String.format("이미 존재하는 코드입니다: %s (부모 ID: %d)", createDto.getCode(), parent.getId()));
      }
    } else {
      if (masterCodeRepository.existsByCodeAndParentIsNull(createDto.getCode())) {
        throw new IllegalArgumentException("이미 존재하는 루트 코드입니다: " + createDto.getCode());
      }
    }

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

    saved.recalculateTreeFields();
    masterCodeRepository.save(saved);

    log.info("MasterCode 생성: code={}, depth={}, parentId={}", saved.getCode(), saved.getDepth(),
        parent != null ? parent.getId() : "ROOT");

    evictCacheForNode(saved);
    return masterCodeDtoMapper.toResponse(saved);
  }

  public MasterCodeResponse updateNode(Long id, MasterCodeUpdate updateDto) {
    MasterCode entity = findById(id);

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

  public MasterCodeResponse moveNode(Long id, MasterCodeMoveRequest moveRequest) {
    MasterCode entity = findById(id);

    MasterCode newParent = null;
    if (moveRequest.getNewParentId() != null) {
      newParent = findById(moveRequest.getNewParentId());

      if (newParent.getId().equals(id)) {
        throw new IllegalArgumentException("자기 자신으로 이동할 수 없습니다");
      }
      if (newParent.getPath() != null && newParent.getPath().contains("/" + id + "/")) {
        throw new IllegalArgumentException("자기 하위 노드로 이동할 수 없습니다");
      }
    }

    String oldRootCode = findRootCode(entity);

    entity.setParent(newParent);
    entity.recalculateTreeFields();

    MasterCode saved = masterCodeRepository.save(entity);
    entityManager.flush();

    recalculateChildrenTreeFields(saved);

    entityManager.refresh(saved);

    log.info("MasterCode 이동: id={}, code={}, newParentId={}", saved.getId(), saved.getCode(),
        newParent != null ? newParent.getId() : "ROOT");

    String newRootCode = findRootCode(saved);
    cacheService.evictByRootCode(oldRootCode);
    if (!oldRootCode.equals(newRootCode)) {
      cacheService.evictByRootCode(newRootCode);
    }

    return masterCodeDtoMapper.toResponse(saved);
  }

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

  private String findRootCode(MasterCode node) {
    MasterCode current = node;
    while (current.getParent() != null) {
      current = current.getParent();
    }
    return current.getCode();
  }

  private void recalculateChildrenTreeFields(MasterCode parent) {
    List<MasterCode> children = masterCodeRepository.findByParentIdAndIsActiveTrueOrderBySortAscCodeAsc(parent.getId());
    for (MasterCode child : children) {
      child.setDepth(parent.getDepth() + 1);
      child.setPath(parent.getPath() + "/" + child.getId());
      masterCodeRepository.save(child);
      recalculateChildrenTreeFields(child);
    }
  }

  private void evictCacheForNode(MasterCode node) {
    String rootCode = findRootCode(node);
    cacheService.evictByRootCode(rootCode);
  }
}

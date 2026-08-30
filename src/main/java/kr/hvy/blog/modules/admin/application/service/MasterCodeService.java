package kr.hvy.blog.modules.admin.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.admin.application.MasterCodeAttributeSanitizer;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeChildrenOrderRequest;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeCreate;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeMoveRequest;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeUpdate;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.security.SecurityUtils;
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
  private final MasterCodeAttributeSanitizer sanitizer;

  // ========== 트리 조회 (캐시 경유) ==========

  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> getFullTree() {
    return sanitizeIfNotAdmin(masterCodeQuery.getFullTree());
  }

  @Transactional(readOnly = true)
  public List<MasterCodeTreeResponse> getSubTree(String rootCode) {
    return sanitizeIfNotAdmin(masterCodeQuery.getSubTree(rootCode));
  }

  /**
   * 현재 사용자가 ROLE_ADMIN 이 아니면 민감 attribute 를 제거한 트리를 반환한다(서비스 레벨 중앙 결정).
   * <p>
   * 엔드포인트 분기 대신 실제 인증 주체의 역할로 sanitize 여부를 정하므로, 어떤 컨트롤러를 거치든
   * 비관리자에게는 민감 attribute 가 노출되지 않는다. {@link MasterCodeAttributeSanitizer} 는 캐시 DTO 를
   * 변경하지 않고 새 복사본을 생성하므로 L1/L2 캐시 오염이 없다.
   */
  private List<MasterCodeTreeResponse> sanitizeIfNotAdmin(List<MasterCodeTreeResponse> trees) {
    return SecurityUtils.hasAdminRole() ? trees : sanitizer.sanitizeTrees(trees);
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
  public MasterCodeResponse getNode(String id) {
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
            String.format("이미 존재하는 코드입니다: %s (부모 ID: %s)", createDto.getCode(), parent.getId()));
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

    // id 를 @PrePersist 에서 만들기 때문에 path 도 같은 시점에 확정된다 —
    // 예전의 save → flush → refresh → recalculateTreeFields → save (2회 저장)가 필요 없다.
    // 그 두 번째 저장을 빠뜨리면 path 가 NULL 로 남아 서브트리 조회가 죽었었다.
    MasterCode saved = masterCodeRepository.save(entity);

    log.info("MasterCode 생성: id={}, code={}, depth={}, path={}, parentId={}",
        saved.getId(), saved.getCode(), saved.getDepth(), saved.getPath(),
        parent != null ? parent.getId() : "ROOT");

    evictCacheForNode(saved);
    return masterCodeDtoMapper.toResponse(saved);
  }

  public MasterCodeResponse updateNode(String id, MasterCodeUpdate updateDto) {
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
              String.format("이미 존재하는 코드입니다: %s (부모 ID: %s)", updateDto.getCode(), entity.getParent().getId()));
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

  public DeleteResponse<String> deleteNode(String id) {
    MasterCode entity = findById(id);

    long childCount = masterCodeRepository.countByParentIdAndIsActiveTrue(id);
    if (childCount > 0) {
      throw new IllegalArgumentException("하위 노드가 존재하여 삭제할 수 없습니다. 하위 노드 수: " + childCount);
    }

    String deletedId = entity.getId();
    masterCodeRepository.delete(entity);

    log.info("MasterCode 삭제: id={}, code={}", deletedId, entity.getCode());

    evictCacheForNode(entity);

    return DeleteResponse.<String>builder()
        .id(deletedId)
        .build();
  }

  public MasterCodeResponse moveNode(String id, MasterCodeMoveRequest moveRequest) {
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

  /**
   * 한 부모의 자식 정렬순서를 <b>한 트랜잭션에서</b> 일괄 재부여한다.
   * <p>
   * 배열 위치가 곧 sort(1..n) 다. 형제마다 PUT 을 날리던 기존 방식은 중간에 실패하면 순서가 반쯤
   * 어긋난 상태로 남았고, 같은 캐시 무효화를 형제 수만큼 반복했다. 여기서는 evict 가 <b>정확히 1회</b>다
   * (정렬은 부모를 바꾸지 않으므로 rootCode 가 불변이다).
   * <p>
   * depth 를 보지 않는다 — parentId 가 루트면 그룹 재정렬, 그룹이면 링크 재정렬이다.
   * <p>
   * <b>벌크 JPQL UPDATE 를 쓰지 않는 이유</b>: {@code MasterCode} 의 {@code @PreUpdate} 가
   * updatedAt/updatedBy 를 채우는데 벌크 UPDATE 는 콜백을 타지 않아 감사 이력이 조용히 빈다.
   * 형제 수가 수십 수준이라 정확성이 성능보다 비싼 구간이다.
   */
  public List<MasterCodeResponse> reorderChildren(String parentId, MasterCodeChildrenOrderRequest orderRequest) {
    MasterCode parent = findById(parentId);
    List<String> orderedIds = orderRequest.getOrderedIds();

    // 1) 중복 검사. 중복이 있으면 두 노드가 같은 sort 를 받아 순서가 code 폴백으로 조용히 무너진다.
    Set<String> requested = new LinkedHashSet<>(orderedIds);
    if (requested.size() != orderedIds.size()) {
      throw new IllegalArgumentException("정렬 요청에 중복된 ID 가 있습니다");
    }

    // 2) 자식 전체 로딩(비활성 포함). 관리 화면이 보지 못하는 비활성 형제까지 함께 재부여해야
    //    나중에 다시 활성화됐을 때 sort 가 겹치지 않는다.
    List<MasterCode> children = masterCodeRepository.findByParentIdOrderBySortAscCodeAsc(parentId);
    Map<String, MasterCode> byId = children.stream()
        .collect(Collectors.toMap(MasterCode::getId, Function.identity()));

    // 3) 이 부모의 자식이 아닌 ID 를 두 부류로 가른다.
    //    · DB 에 아예 없다 → 그 사이 삭제된 것. 다른 탭에서 지우고 드래그하면 나는 평범한 레이스다.
    //      이 프로젝트는 400/500 을 가리지 않고 모든 예외가 Slack #hvy-error 를 울리므로 던지지 않는다.
    //    · DB 엔 있으나 부모가 다르다 → 클라이언트 버그이거나 조작된 요청. 거부한다.
    List<String> unknownIds = orderedIds.stream().filter(id -> !byId.containsKey(id)).toList();
    if (!unknownIds.isEmpty()) {
      List<String> foreignIds = unknownIds.stream().filter(masterCodeRepository::existsById).toList();
      if (!foreignIds.isEmpty()) {
        throw new IllegalArgumentException("이 노드의 자식이 아닌 ID 가 포함되어 있습니다: " + foreignIds);
      }
      log.warn("정렬 요청에 이미 삭제된 ID 가 있어 건너뜁니다: parentId={}, ids={}", parentId, unknownIds);
    }

    // 4) sort 재부여. 요청 순서대로 1..n 이고, 요청에 없던 형제(비활성·동시 생성분)는 기존 상대 순서를
    //    지킨 채 뒤에 이어붙인다. dirty checking 이라 @PreUpdate 가 정상 동작한다.
    int nextSort = 1;
    for (String id : orderedIds) {
      MasterCode child = byId.get(id);
      if (child != null) {
        child.setSort(nextSort++);
      }
    }
    for (MasterCode child : children) {
      if (!requested.contains(child.getId())) {
        child.setSort(nextSort++);
      }
    }

    // 5) flush 를 evict 앞에 둔다 — 제약 위반이 있다면 캐시를 비우기 전에 드러나야 한다.
    entityManager.flush();

    log.info("MasterCode 정렬 변경: parentId={}, code={}, count={}",
        parentId, parent.getCode(), orderedIds.size());

    // 6) 캐시 무효화 1회. 정렬은 부모를 바꾸지 않아 rootCode 가 불변이다.
    //    ⚠️ findRootCode 는 LAZY parent 를 타므로 반드시 트랜잭션 안에서 호출해야 한다.
    evictCacheForNode(parent);

    return children.stream()
        .sorted(Comparator.comparing(MasterCode::getSort).thenComparing(MasterCode::getCode))
        .map(masterCodeDtoMapper::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<MasterCodeResponse> searchNodes(String keyword) {
    List<MasterCode> results = masterCodeRepository.searchByNameOrCode(keyword);
    return masterCodeDtoMapper.toResponseList(results);
  }

  // ========== 내부 헬퍼 ==========

  private MasterCode findById(String id) {
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

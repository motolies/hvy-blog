package kr.hvy.blog.modules.admin.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import org.springframework.stereotype.Component;

/**
 * 공개(비인증) 마스터코드 응답에서 민감 attribute 를 제거하는 sanitizer.
 * <p>
 * 루트 노드의 {@code attributeSchema} 에 정의된 key 중 {@code sensitive != "true"} 인 것만
 * 공개로 간주하는 <b>fail-closed allowlist</b> 방식이다. 스키마에 등록되지 않은 key 나
 * sensitive 로 표시된 key(예: Claude 토큰)는 응답에서 제거된다.
 * <p>
 * 캐시(L1/L2)에 보관된 불변 DTO 를 변경하지 않기 위해 항상 새 인스턴스로 복사한다.
 */
@Component
public class MasterCodeAttributeSanitizer {

  /**
   * 서브트리 목록을 sanitize 한다. 각 트리의 최상위(루트) 노드 attributeSchema 를 기준으로
   * 해당 트리 전체 노드의 attributes 를 공개 key 만 남긴다.
   */
  public List<MasterCodeTreeResponse> sanitizeTrees(List<MasterCodeTreeResponse> trees) {
    if (trees == null) {
      return List.of();
    }
    List<MasterCodeTreeResponse> result = new ArrayList<>(trees.size());
    for (MasterCodeTreeResponse tree : trees) {
      if (tree != null) {
        result.add(sanitizeNode(tree, publicKeys(tree.getAttributeSchema())));
      }
    }
    return result;
  }

  // 루트 스키마에서 공개 가능한(sensitive != "true") key 집합을 추출한다.
  private Set<String> publicKeys(List<Map<String, String>> schema) {
    if (schema == null) {
      return Set.of();
    }
    return schema.stream()
        .filter(entry -> entry != null && entry.get("key") != null)
        .filter(entry -> !"true".equalsIgnoreCase(entry.get("sensitive")))
        .map(entry -> entry.get("key"))
        .collect(Collectors.toSet());
  }

  // 노드를 불변 복사하며 attributes 는 공개 key 로 필터링, attributeSchema 는 제거, 자식은 재귀 처리한다.
  private MasterCodeTreeResponse sanitizeNode(MasterCodeTreeResponse node, Set<String> publicKeys) {
    List<MasterCodeTreeResponse> sanitizedChildren = new ArrayList<>();
    if (node.getChildren() != null) {
      for (MasterCodeTreeResponse child : node.getChildren()) {
        if (child != null) {
          sanitizedChildren.add(sanitizeNode(child, publicKeys));
        }
      }
    }
    return MasterCodeTreeResponse.builder()
        .id(node.getId())
        .code(node.getCode())
        .name(node.getName())
        .description(node.getDescription())
        .attributes(filterAttributes(node.getAttributes(), publicKeys))
        .attributeSchema(List.of()) // 공개 응답에는 스키마 메타데이터를 노출하지 않는다.
        .depth(node.getDepth())
        .sort(node.getSort())
        .isActive(node.getIsActive())
        .children(sanitizedChildren)
        .build();
  }

  // attributes 에서 공개 key 만 남긴 새 맵을 반환한다(원본 미변경).
  private Map<String, Object> filterAttributes(Map<String, Object> attributes, Set<String> publicKeys) {
    if (attributes == null || attributes.isEmpty() || publicKeys.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      if (publicKeys.contains(entry.getKey())) {
        filtered.put(entry.getKey(), entry.getValue());
      }
    }
    return filtered;
  }
}

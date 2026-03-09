package kr.hvy.blog.modules.admin.application.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 마스터코드 트리 응답 DTO (재귀 구조)
 */
@Value
@Builder
@Jacksonized
public class MasterCodeTreeResponse {

  Long id;
  String code;
  String name;
  String description;
  Map<String, Object> attributes;
  List<Map<String, String>> attributeSchema;
  Integer depth;
  Integer sort;
  Boolean isActive;

  @Builder.Default
  List<MasterCodeTreeResponse> children = new ArrayList<>();
}

package kr.hvy.blog.modules.admin.application.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 마스터코드 단건 응답 DTO (플랫 구조)
 */
@Value
@Builder
@Jacksonized
public class MasterCodeResponse {

  Long id;
  String code;
  String name;
  String description;
  Map<String, Object> attributes;
  List<Map<String, String>> attributeSchema;
  Integer depth;
  Long parentId;
  Integer sort;
  Boolean isActive;
  LocalDateTime createdAt;
  String createdBy;
  LocalDateTime updatedAt;
  String updatedBy;
  Integer childCount;
}

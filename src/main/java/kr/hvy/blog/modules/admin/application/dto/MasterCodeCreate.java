package kr.hvy.blog.modules.admin.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 마스터코드 생성 요청 DTO
 */
@Value
@Builder
@Jacksonized
public class MasterCodeCreate {

  /**
   * 부모 노드 ID (NULL이면 루트 생성)
   */
  Long parentId;

  /**
   * 코드값
   */
  @NotBlank(message = "코드는 필수입니다")
  @Size(max = 64, message = "코드는 64자를 초과할 수 없습니다")
  String code;

  /**
   * 코드명
   */
  @NotBlank(message = "이름은 필수입니다")
  @Size(max = 128, message = "이름은 128자를 초과할 수 없습니다")
  String name;

  /**
   * 설명
   */
  @Size(max = 512, message = "설명은 512자를 초과할 수 없습니다")
  String description;

  /**
   * 속성값 (JSONB)
   */
  Map<String, Object> attributes;

  /**
   * 속성 스키마 (루트 노드 전용)
   */
  List<Map<String, String>> attributeSchema;

  /**
   * 정렬순서 (0이면 자동 설정)
   */
  Integer sort;

  /**
   * 활성화 여부
   */
  Boolean isActive;
}

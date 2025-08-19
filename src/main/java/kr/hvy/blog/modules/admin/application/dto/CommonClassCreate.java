package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * CommonClass 생성 요청 DTO
 */
@Value
@Builder
@Jacksonized
public class CommonClassCreate {

  /**
   * 클래스명 (PK)
   */
  @NotBlank(message = "클래스명은 필수입니다")
  @Size(max = 64, message = "클래스명은 64자 이하여야 합니다")
  String name;

  /**
   * 표시명
   */
  @Size(max = 128, message = "표시명은 128자 이하여야 합니다")
  String displayName;

  /**
   * 설명
   */
  @Size(max = 512, message = "설명은 512자 이하여야 합니다")
  String description;

  /**
   * 동적 속성 이름들
   */
  @Size(max = 64, message = "속성1 이름은 64자 이하여야 합니다")
  String attribute1Name;

  @Size(max = 64, message = "속성2 이름은 64자 이하여야 합니다")
  String attribute2Name;

  @Size(max = 64, message = "속성3 이름은 64자 이하여야 합니다")
  String attribute3Name;

  @Size(max = 64, message = "속성4 이름은 64자 이하여야 합니다")
  String attribute4Name;

  @Size(max = 64, message = "속성5 이름은 64자 이하여야 합니다")
  String attribute5Name;

  /**
   * 활성화 여부 (기본값: true)
   */
  @Builder.Default
  Boolean isActive = true;
}

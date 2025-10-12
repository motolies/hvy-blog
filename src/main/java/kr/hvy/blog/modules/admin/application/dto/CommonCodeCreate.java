package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

/**
 * CommonCode 생성 요청 DTO
 */
@Value
@Builder
@Jacksonized
public class CommonCodeCreate {

  /**
   * 클래스 코드
   */
  @NotBlank(message = "클래스 코드는 필수입니다")
  @Size(max = 64, message = "클래스 코드는 64자 이하여야 합니다")
  String classCode;

  /**
   * 코드값
   */
  @NotBlank(message = "코드값은 필수입니다")
  @Size(max = 32, message = "코드값은 32자 이하여야 합니다")
  String code;

  /**
   * 코드명
   */
  @NotBlank(message = "코드명은 필수입니다")
  @Size(max = 64, message = "코드명은 64자 이하여야 합니다")
  String name;

  /**
   * 설명
   */
  @Size(max = 512, message = "설명은 512자 이하여야 합니다")
  String description;

  /**
   * 동적 속성값들
   */
  @Size(max = 128, message = "속성1 값은 128자 이하여야 합니다")
  String attribute1Value;

  @Size(max = 128, message = "속성2 값은 128자 이하여야 합니다")
  String attribute2Value;

  @Size(max = 128, message = "속성3 값은 128자 이하여야 합니다")
  String attribute3Value;

  @Size(max = 128, message = "속성4 값은 128자 이하여야 합니다")
  String attribute4Value;

  @Size(max = 128, message = "속성5 값은 128자 이하여야 합니다")
  String attribute5Value;

  /**
   * 하위 클래스 코드 (NULL이면 leaf 노드)
   */
  @Size(max = 64, message = "하위 클래스 코드는 64자 이하여야 합니다")
  String childClassCode;

  /**
   * 정렬순서 (기본값: 0)
   */
  @Min(value = 0, message = "정렬순서는 0 이상이어야 합니다")
  @Builder.Default
  Integer sort = 0;

  /**
   * 활성화 여부 (기본값: true)
   */
  @Builder.Default
  Boolean isActive = true;
}

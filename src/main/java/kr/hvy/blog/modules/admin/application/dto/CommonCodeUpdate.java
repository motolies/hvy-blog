package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

/**
 * CommonCode 수정 요청 DTO
 */
@Value
@Builder
@Jacksonized
public class CommonCodeUpdate {

  /**
   * 코드명
   */
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
   * 하위 클래스명 (NULL이면 leaf 노드)
   */
  @Size(max = 64, message = "하위 클래스명은 64자 이하여야 합니다")
  String childClassName;

  /**
   * 정렬순서
   */
  @Min(value = 0, message = "정렬순서는 0 이상이어야 합니다")
  Integer sort;

  /**
   * 활성화 여부
   */
  Boolean isActive;
}

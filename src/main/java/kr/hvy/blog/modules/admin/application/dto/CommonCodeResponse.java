package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CommonCode 응답 DTO
 */
@Value
@Builder
@Jacksonized
public class CommonCodeResponse {

  /**
   * 클래스명
   */
  String className;

  /**
   * 코드값
   */
  String code;

  /**
   * 코드명
   */
  String name;

  /**
   * 설명
   */
  String description;

  /**
   * 동적 속성들 (Map 형태로 제공)
   */
  Map<String, String> attributes;

  /**
   * 개별 속성값들 (호환성을 위해 유지)
   */
  String attribute1Value;
  String attribute2Value;
  String attribute3Value;
  String attribute4Value;
  String attribute5Value;

  /**
   * 하위 클래스명 (NULL이면 leaf 노드)
   */
  String childClassName;

  /**
   * 하위 코드 존재 여부 (계산된 필드)
   * API 호환성을 위해 제공
   */
  public Boolean getHasChildren() {
    return childClassName != null && !childClassName.trim().isEmpty();
  }

  /**
   * 정렬순서
   */
  Integer sort;

  /**
   * 활성화 여부
   */
  Boolean isActive;

  /**
   * 생성 정보
   */
  LocalDateTime createdAt;
  String createdBy;

  /**
   * 수정 정보
   */
  LocalDateTime updatedAt;
  String updatedBy;

  /**
   * 하위 코드들 (계층 구조용, 옵션)
   */
  List<CommonCodeResponse> children;

  /**
   * 소속 클래스 정보 (옵션)
   */
  CommonClassResponse commonClass;
}

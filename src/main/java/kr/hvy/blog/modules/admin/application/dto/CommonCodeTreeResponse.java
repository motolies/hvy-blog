package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

/**
 * CommonCode 계층 구조 응답 DTO
 * 트리 형태의 코드 조회 시 사용
 */
@Value
@Builder
@Jacksonized
public class CommonCodeTreeResponse {

  /**
   * 클래스명
   */
  String className;


  /**
   * 이 클래스에 속한 코드들
   */
  List<CommonCodeItemResponse> codes;

  /**
   * 전체 코드 개수
   */
  Integer totalCount;

  /**
   * 계층 구조를 가진 코드 아이템
   */
  @Value
  @Builder
  @Jacksonized
  public static class CommonCodeItemResponse {

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
     * 동적 속성들 (key-value 형태)
     */
    Map<String, String> attributes;

    /**
     * 하위 클래스 코드
     */
    String childClassCode;

    /**
     * 하위 코드 존재 여부 (계산된 필드)
     */
    public Boolean getHasChildren() {
      return childClassCode != null && !childClassCode.trim().isEmpty();
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
     * 하위 코드들 (재귀 구조)
     */
    List<CommonCodeItemResponse> children;
  }

  /**
   * 플랫(평면) 형태의 코드 응답 DTO
   * select box 등에서 사용
   */
  @Value
  @Builder
  @Jacksonized
  public static class CommonCodeFlatResponse {

    /**
     * 전체 경로 (예: "지역 > 서울 > 강남구")
     */
    String fullPath;

    /**
     * 클래스 코드
     */
    String classCode;

    /**
     * 코드값
     */
    String code;

    /**
     * 코드명
     */
    String name;

    /**
     * 계층 레벨 (0부터 시작)
     */
    Integer level;

    /**
     * 동적 속성들
     */
    Map<String, String> attributes;

    /**
     * 정렬순서
     */
    Integer sort;
  }
}

package kr.hvy.blog.modules.admin.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공통코드 트리 노드 통합 응답 DTO
 * CLASS와 CODE를 모두 표현할 수 있는 통합 구조
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommonCodeTreeNodeResponse {

  /**
   * 노드 타입: CLASS 또는 CODE
   */
  String type;

  /**
   * 고유 ID (트리 렌더링용)
   */
  String id;

  /**
   * 코드값
   * - CLASS인 경우: 클래스의 코드 (기존 name)
   * - CODE인 경우: 코드값
   */
  String code;

  /**
   * 표시명
   * - CLASS인 경우: 클래스의 표시명 (기존 displayName)
   * - CODE인 경우: 코드명 (기존 name)
   */
  String name;

  /**
   * 설명
   */
  String description;

  /**
   * 활성화 여부
   */
  Boolean isActive;

  /**
   * 정렬순서 (CODE인 경우에만)
   */
  Integer sort;

  /**
   * 속성 이름들 (CLASS인 경우에만)
   */
  String attribute1Name;
  String attribute2Name;
  String attribute3Name;
  String attribute4Name;
  String attribute5Name;

  /**
   * 속성 값들 (CODE인 경우에만)
   */
  String attribute1Value;
  String attribute2Value;
  String attribute3Value;
  String attribute4Value;
  String attribute5Value;

  /**
   * 하위 클래스명 (CODE인 경우에만)
   */
  String childClassName;

  /**
   * 소속 클래스명 (CODE인 경우에만)
   */
  String classCode;

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
   * 자식 노드들 (CLASS는 CODE 자식, CODE는 childClass 자식 보유 가능)
   */
  List<CommonCodeTreeNodeResponse> children;

  /**
   * CLASS 노드 생성 팩토리 메서드
   */
  public static CommonCodeTreeNodeResponse fromClass(CommonClassResponse classResponse, List<CommonCodeTreeNodeResponse> codeChildren) {
    return CommonCodeTreeNodeResponse.builder()
        .type("CLASS")
        .id("CLASS_" + classResponse.getCode())
        .code(classResponse.getCode())  // 클래스의 name을 code로
        .name(classResponse.getName())  // 클래스의 displayName을 name으로
        .description(classResponse.getDescription())
        .isActive(classResponse.getIsActive())
        .attribute1Name(classResponse.getAttribute1Name())
        .attribute2Name(classResponse.getAttribute2Name())
        .attribute3Name(classResponse.getAttribute3Name())
        .attribute4Name(classResponse.getAttribute4Name())
        .attribute5Name(classResponse.getAttribute5Name())
        .createdAt(classResponse.getCreatedAt())
        .createdBy(classResponse.getCreatedBy())
        .updatedAt(classResponse.getUpdatedAt())
        .updatedBy(classResponse.getUpdatedBy())
        .children(codeChildren)
        .build();
  }

  /**
   * CODE 노드 생성 팩토리 메서드
   */
  public static CommonCodeTreeNodeResponse fromCode(CommonCodeResponse codeResponse, CommonCodeTreeNodeResponse childClassNode) {
    return CommonCodeTreeNodeResponse.builder()
        .type("CODE")
        .id("CODE_" + codeResponse.getClassCode() + "_" + codeResponse.getCode())
        .code(codeResponse.getCode())
        .name(codeResponse.getName())
        .description(codeResponse.getDescription())
        .isActive(codeResponse.getIsActive())
        .sort(codeResponse.getSort())
        .classCode(codeResponse.getClassCode())
        .attribute1Value(codeResponse.getAttribute1Value())
        .attribute2Value(codeResponse.getAttribute2Value())
        .attribute3Value(codeResponse.getAttribute3Value())
        .attribute4Value(codeResponse.getAttribute4Value())
        .attribute5Value(codeResponse.getAttribute5Value())
        .childClassName(codeResponse.getChildClassCode())
        .createdAt(codeResponse.getCreatedAt())
        .createdBy(codeResponse.getCreatedBy())
        .updatedAt(codeResponse.getUpdatedAt())
        .updatedBy(codeResponse.getUpdatedBy())
        .children(childClassNode != null ? List.of(childClassNode) : null)
        .build();
  }
}

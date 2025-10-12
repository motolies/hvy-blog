package kr.hvy.blog.modules.admin.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CommonClass 응답 DTO
 */
@Value
@Builder
@Jacksonized
public class CommonClassResponse {

  /**
   * 노드 타입 (고정값: CLASS)
   */
  @Builder.Default
  String type = "CLASS";

  /**
   * 클래스 코드 (PK)
   */
  String code;

  /**
   * 클래스명
   */
  String name;

  /**
   * 설명
   */
  String description;

  /**
   * 동적 속성 이름들
   */
  String attribute1Name;
  String attribute2Name;
  String attribute3Name;
  String attribute4Name;
  String attribute5Name;

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
   * 이 클래스에 속한 코드들 (옵션)
   */
  List<CommonCodeResponse> codes;

  /**
   * 코드 개수
   */
  Integer codeCount;
}

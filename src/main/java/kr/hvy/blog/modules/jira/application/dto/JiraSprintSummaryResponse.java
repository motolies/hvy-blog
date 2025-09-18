package kr.hvy.blog.modules.jira.application.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Jira 스프린트 서머리 응답 DTO
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class JiraSprintSummaryResponse {

  /**
   * 조회 연도
   */
  private String year;

  /**
   * 스프린트 목록 (2024-SP-01 ~ 2024-SP-26)
   */
  private List<String> sprints;

  /**
   * 작업자별 스프린트 서머리 목록
   */
  private List<AssigneeSummary> assigneeSummaries;

  /**
   * 스프린트별 합계 (컬럼 합계)
   */
  private Map<String, BigDecimal> sprintTotals;

  /**
   * 작업자별 스프린트 서머리
   */
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  @AllArgsConstructor
  @Builder
  public static class AssigneeSummary {

    /**
     * 담당자명
     */
    private String assignee;

    /**
     * 스프린트별 스토리포인트 합계
     * Key: 스프린트명 (예: 2024-SP-01)
     * Value: 해당 스프린트에서 완료한 스토리포인트 합계
     */
    private Map<String, BigDecimal> sprintStoryPoints;

    /**
     * 해당 작업자의 연간 총 스토리포인트 합계
     */
    private BigDecimal totalStoryPoints;
  }
}
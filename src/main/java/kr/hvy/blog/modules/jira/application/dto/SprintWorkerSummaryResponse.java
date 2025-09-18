package kr.hvy.blog.modules.jira.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 스프린트-작업자 서머리 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SprintWorkerSummaryResponse {

    private String sprint;           // 스프린트
    private String assignee;         // 작업자
    private String issueKey;         // 이슈
    private String status;           // 상태
    private String summary;          // 서머리
    private LocalDate startDate;     // 시작일
    private BigDecimal totalTimeHours; // 작업로그(sum(timeHours))
    private BigDecimal storyPoints;  // 스토리포인트

}
package kr.hvy.blog.modules.jira.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 작업로그 상세 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorklogDetailResponse {

    private String issueKey;        // 이슈
    private String author;          // 작업자
    private String comment;         // 작업 코멘트
    private LocalDateTime started;  // 작업시작시간(started)
    private BigDecimal timeHours;   // 작업시간(timeHours)

}
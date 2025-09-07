package kr.hvy.blog.modules.jira.application.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Jira 이슈 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraIssueDto {

    private Long id;
    private Long issueId;
    private String issueKey;
    private String issueLink;
    private String summary;
    private String issueType;
    private String status;
    private String assignee;
    private String components;
    private BigDecimal storyPoints;
    private LocalDate startDate;
    private String sprint;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<JiraWorklogDto> worklogs;

}
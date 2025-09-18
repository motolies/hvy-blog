package kr.hvy.blog.modules.jira.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Jira 워크로그 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorklogDto {

    private Long id;
    private String issueKey;
    private String issueType;
    private String status;
    private String issueLink;
    private String summary;
    private String author;
    private String components;
    private String timeSpent;
    private BigDecimal timeHours;
    private String comment;
    private LocalDateTime started;
    private String worklogId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
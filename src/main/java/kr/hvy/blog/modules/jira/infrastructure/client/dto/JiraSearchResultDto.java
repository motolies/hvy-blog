package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Jira 이슈 검색 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraSearchResultDto {
    
    private List<JiraIssueResponse> issues;
    private Integer total;
    private Integer maxResults;
    private Integer startAt;
}
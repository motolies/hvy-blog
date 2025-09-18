package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/**
 * Jira 이슈 검색 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraSearchResultDto {

  private List<JiraIssueResponse> issues;

  private String nextPageToken;
  private boolean isLast;
}
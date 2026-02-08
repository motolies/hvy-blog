package kr.hvy.blog.modules.jira.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/**
 * Jira 이슈 검색 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraSearchResultResponse {

  private List<JiraIssueResponse> issues;

  private String nextPageToken;
  private boolean isLast;
}

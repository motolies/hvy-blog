package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/**
 * Jira Changelog 페이징 API 응답 DTO
 * GET /rest/api/3/issue/{issueIdOrKey}/changelog
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraChangelogPageResponse {

  private Integer startAt;
  private Integer maxResults;
  private Integer total;
  private List<JiraChangelogResponse.ChangelogHistoryDto> values;
}

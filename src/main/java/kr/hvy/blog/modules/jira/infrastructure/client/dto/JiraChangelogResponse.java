package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.Data;

/**
 * Jira Changelog API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraChangelogResponse {

  private List<ChangelogHistoryDto> histories;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChangelogHistoryDto {

    private String id;
    private ZonedDateTime created;
    private List<ChangelogItemDto> items;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChangelogItemDto {

    private String field;
    private String fieldtype;
    private String from;
    private String fromString;
    private String to;
    private String toString;
  }
}

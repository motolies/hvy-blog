package kr.hvy.blog.modules.jira.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

/**
 * Jira 이슈 API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraIssueResponse {

  private Long id; // 이슈의 숫자 ID (bulk worklog API용)
  private String key;
  private String self; // link URL
  private JiraFieldsDto fields;
  private JiraChangelogResponse changelog; // changelog expand 시 포함

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraFieldsDto {

    private String summary;

    @JsonProperty("issuetype")
    private JiraIssueTypeDto issueType;

    private JiraStatusDto status;
    private JiraUserDto assignee;
    private List<JiraComponentDto> components;

    @JsonProperty("customfield_10026") // Story Points
    private Double storyPoints;

    @JsonProperty("customfield_10015") // Start Date
    private String startDate;

    @JsonProperty("customfield_10280") // sprint
    private String sprint;

    private JiraWorklogContainerDto worklog;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraIssueTypeDto {

    private String name;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraStatusDto {

    private String name;
    private JiraStatusCategoryDto statusCategory;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraStatusCategoryDto {

    private String key;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraUserDto {

    private String displayName;
    private String accountId;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraComponentDto {

    private String name;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class JiraWorklogContainerDto {

    private Integer total;
    private List<JiraWorklogResponse> worklogs;
  }
}
package kr.hvy.blog.modules.jira.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.hvy.blog.modules.jira.application.dto.JiraIssueDto;
import kr.hvy.blog.modules.jira.application.dto.JiraWorklogDto;
import kr.hvy.blog.modules.jira.infrastructure.client.dto.JiraIssueResponse;
import kr.hvy.blog.modules.jira.infrastructure.client.dto.JiraSearchResultDto;
import kr.hvy.blog.modules.jira.infrastructure.client.dto.JiraServerInfoDto;
import kr.hvy.blog.modules.jira.infrastructure.client.dto.JiraWorklogResponse;
import kr.hvy.blog.modules.jira.infrastructure.config.JiraProperties;
import kr.hvy.common.infrastructure.client.rest.RestApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Jira REST API 클라이언트 래퍼
 */
@Slf4j
@Component
public class JiraClientWrapper {

  private final RestApi jiraRestApi;
  private final JiraProperties jiraProperties;

  public JiraClientWrapper(@Qualifier("jiraRestApi") RestApi jiraRestApi,
      JiraProperties jiraProperties) {
    this.jiraRestApi = jiraRestApi;
    this.jiraProperties = jiraProperties;
  }

  /**
   * 프로젝트의 모든 이슈를 조회합니다.
   */
  public List<JiraIssueDto> getAllIssuesFromProject() {
    try {
      // 첫 번째 요청으로 총 이슈 수 파악
      int totalIssues = getTotalIssueCount();
//      int totalIssues = 100;

      if (totalIssues == 0) {
        log.info("프로젝트 {}에 이슈가 없습니다.", jiraProperties.getProjectKey());
        return new ArrayList<>();
      }

      log.info("프로젝트 {}에서 총 {}개의 이슈를 순차적으로 조회합니다.", jiraProperties.getProjectKey(), totalIssues);

      // 순차 처리를 위한 배치 계산
      int batchSize = 100;
      List<JiraIssueDto> allIssues = new ArrayList<>();
      int totalBatches = (totalIssues + batchSize - 1) / batchSize;

      // 배치별 순차 처리
      for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
        final int startAt = batchIndex * batchSize;
        final int currentBatchSize = Math.min(batchSize, totalIssues - startAt);

        log.debug("배치 {}/{}를 처리 중입니다. (startAt: {}, batchSize: {})",
            batchIndex + 1, totalBatches, startAt, currentBatchSize);

        List<JiraIssueDto> batchIssues = fetchIssueBatch(startAt, currentBatchSize);
        if (batchIssues != null && !batchIssues.isEmpty()) {
          allIssues.addAll(batchIssues);
        }
      }

      log.info("프로젝트 {}에서 {}개의 이슈를 성공적으로 조회했습니다. (총 배치: {}개)",
          jiraProperties.getProjectKey(), allIssues.size(), totalBatches);

      return allIssues;

    } catch (Exception e) {
      log.error("Jira에서 이슈 조회 중 오류가 발생했습니다: {}", e.getMessage(), e);
      throw new RuntimeException("Jira 이슈 조회 실패", e);
    }
  }

  /**
   * 총 이슈 수를 조회합니다.
   */
  private int getTotalIssueCount() {
    try {
      String jql = String.format("project = %s ORDER BY updated DESC", jiraProperties.getProjectKey());

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("jql", jql);
      params.add("maxResults", "1");
      params.add("startAt", "0");

      JiraSearchResultDto searchResult = jiraRestApi.get("/rest/api/3/search", params, JiraSearchResultDto.class);
      return searchResult.getTotal();

    } catch (Exception e) {
      log.error("총 이슈 수 조회 중 오류 발생: {}", e.getMessage(), e);
      return 0;
    }
  }

  /**
   * 지정된 범위의 이슈 배치를 조회합니다. 워크로그는 별도 bulk API로 완전히 조회합니다.
   */
  private List<JiraIssueDto> fetchIssueBatch(int startAt, int batchSize) {
    try {
      String jql = String.format("project = %s ORDER BY updated DESC", jiraProperties.getProjectKey());

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("jql", jql);
      params.add("maxResults", String.valueOf(batchSize));
      params.add("startAt", String.valueOf(startAt));
      params.add("fields", "summary,issuetype,assignee,components,customfield_10026,customfield_10015,customfield_10280,worklog,status"); // worklog 기본 포함 (최대 20개)

      JiraSearchResultDto searchResult = jiraRestApi.get("/rest/api/3/search", params, JiraSearchResultDto.class);
      List<JiraIssueResponse> issues = searchResult.getIssues();

      if (issues == null || issues.isEmpty()) {
        log.debug("배치에서 조회된 이슈가 없습니다.");
        return new ArrayList<>();
      }

      // 순차처리: 각 이슈의 워크로그 최적화 조회를 순차적으로 처리
      List<JiraIssueDto> batchIssues = new ArrayList<>();

      for (JiraIssueResponse issue : issues) {
        try {
          // 1. 기본 이슈 정보를 DTO로 변환 (기본 워크로그 제외)
          JiraIssueDto issueDto = convertToApplicationDtoWithoutWorklogs(issue);

          // 2. Python 최적화 로직: total > 19면 별도 API, 그렇지 않으면 기본 워크로그 사용
          List<JiraWorklogDto> worklogs = getOptimizedWorklogs(issue);
          if (worklogs != null && !worklogs.isEmpty()) {
            issueDto.setWorklogs(worklogs);
            log.debug("이슈 {}에서 {}개의 워크로그를 순차 최적화 조회했습니다.", issue.getKey(), worklogs.size());
          }

          batchIssues.add(issueDto);

        } catch (Exception e) {
          log.error("이슈 {} 순차 처리 중 오류 발생: {}", issue.getKey(), e.getMessage());
          // 개별 이슈 실패해도 계속 처리하되, 워크로그 없이 이슈만 반환
          batchIssues.add(convertToApplicationDtoWithoutWorklogs(issue));
        }
      }

      log.debug("순차 배치 처리 완료: {}-{} ({}개 이슈를 순차적으로 처리)",
          startAt + 1, startAt + batchIssues.size(), batchIssues.size());

      return batchIssues;

    } catch (Exception e) {
      log.error("배치 조회 실패 (startAt: {}, batchSize: {}): {}", startAt, batchSize, e.getMessage(), e);
      return new ArrayList<>();
    }
  }


  /**
   * Python 로직을 적용한 워크로그 최적화 조회 - worklog.total <= 19: 기본 워크로그 사용 - worklog.total > 19: 별도 API로 전체 조회
   */
  public List<JiraWorklogDto> getOptimizedWorklogs(JiraIssueResponse issue) {
    try {
      JiraIssueResponse.JiraFieldsDto fields = issue.getFields();

      // 기본 워크로그 정보 확인
      if (fields.getWorklog() == null) {
        log.debug("이슈 {}에 워크로그 정보가 없습니다.", issue.getKey());
        return new ArrayList<>();
      }

      JiraIssueResponse.JiraWorklogContainerDto worklogContainer = fields.getWorklog();
      int totalWorklogsCount = worklogContainer.getTotal() != null ? worklogContainer.getTotal() : 0;

      log.debug("이슈 {} 워크로그: 총 {}개, 기본 포함 {}개",
          issue.getKey(), totalWorklogsCount,
          worklogContainer.getWorklogs() != null ? worklogContainer.getWorklogs().size() : 0);

      // Python 로직: total > 19이면 별도 API로 전체 조회
      if (totalWorklogsCount > 19) {
        log.debug("이슈 {} 워크로그가 {}개로 많음. 별도 API로 전체 조회합니다.",
            issue.getKey(), totalWorklogsCount);
        return getWorklogsForIssue(issue); // 최적화: 이미 가진 issue 객체 활용
      } else {
        // 기본 워크로그 사용
        log.debug("이슈 {} 워크로그가 {}개로 적음. 기본 워크로그를 사용합니다.",
            issue.getKey(), totalWorklogsCount);

        if (worklogContainer.getWorklogs() == null || worklogContainer.getWorklogs().isEmpty()) {
          return new ArrayList<>();
        }

        return worklogContainer.getWorklogs().stream()
            .map(worklog -> convertToApplicationWorklogDto(worklog, issue))
            .collect(Collectors.toList());
      }

    } catch (Exception e) {
      log.error("이슈 {} 최적화 워크로그 조회 중 오류 발생: {}", issue.getKey(), e.getMessage());
      return new ArrayList<>();
    }
  }

  /**
   * 특정 이슈의 워크로그를 조회합니다 (이슈 정보 포함) - 최적화 버전 이미 가진 JiraIssueResponse 객체를 활용하여 불필요한 API 호출 제거
   */
  public List<JiraWorklogDto> getWorklogsForIssue(JiraIssueResponse issue) {
    try {
      String endpoint = String.format("/rest/api/3/issue/%s/worklog", issue.getKey());

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("maxResults", "1000");

      JiraIssueResponse.JiraWorklogContainerDto worklogContainer =
          jiraRestApi.get(endpoint, params, JiraIssueResponse.JiraWorklogContainerDto.class);

      List<JiraWorklogResponse> worklogs = worklogContainer.getWorklogs();
      log.debug("이슈 {}에서 {}개의 워크로그를 조회했습니다.", issue.getKey(), worklogs.size());

      // 이미 가진 이슈 정보 활용 (API 호출 제거!)
      return worklogs.stream()
          .map(worklog -> convertToApplicationWorklogDto(worklog, issue))
          .collect(Collectors.toList());

    } catch (Exception e) {
      log.error("이슈 {}의 워크로그 조회 중 오류가 발생했습니다: {}", issue.getKey(), e.getMessage(), e);
      throw new RuntimeException("워크로그 조회 실패", e);
    }
  }

  /**
   * 특정 이슈의 워크로그를 조회합니다 - 기존 호환성을 위한 메소드
   */
  public List<JiraWorklogDto> getWorklogsForIssue(String issueKey) {
    try {
      String endpoint = String.format("/rest/api/3/issue/%s/worklog", issueKey);

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("maxResults", "1000");

      JiraIssueResponse.JiraWorklogContainerDto worklogContainer =
          jiraRestApi.get(endpoint, params, JiraIssueResponse.JiraWorklogContainerDto.class);

      List<JiraWorklogResponse> worklogs = worklogContainer.getWorklogs();
      log.debug("이슈 {}에서 {}개의 워크로그를 조회했습니다.", issueKey, worklogs.size());

      // 이슈 정보를 가져와서 워크로그에 추가 정보 설정
      JiraIssueResponse issue = getIssueInternal(issueKey);

      return worklogs.stream()
          .map(worklog -> convertToApplicationWorklogDto(worklog, issue))
          .collect(Collectors.toList());

    } catch (Exception e) {
      log.error("이슈 {}의 워크로그 조회 중 오류가 발생했습니다: {}", issueKey, e.getMessage(), e);
      throw new RuntimeException("워크로그 조회 실패", e);
    }
  }

  /**
   * 특정 이슈의 상세 정보를 조회합니다.
   */
  public JiraIssueDto getIssue(String issueKey) {
    JiraIssueResponse infraDto = getIssueInternal(issueKey);
    return convertToApplicationDto(infraDto);
  }

  /**
   * 내부에서 사용하는 이슈 조회 (infrastructure DTO 반환)
   */
  private JiraIssueResponse getIssueInternal(String issueKey) {
    try {
      String endpoint = String.format("/rest/api/3/issue/%s", issueKey);

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("expand", "changelog,worklog");

      return jiraRestApi.get(endpoint, params, JiraIssueResponse.class);

    } catch (Exception e) {
      log.error("이슈 {}의 상세 정보 조회 중 오류가 발생했습니다: {}", issueKey, e.getMessage(), e);
      throw new RuntimeException("Jira 이슈 상세 조회 실패", e);
    }
  }

  /**
   * 내부에서 사용하는 이슈 조회 (issueId로, infrastructure DTO 반환)
   */
  private JiraIssueResponse getIssueByIdInternal(Long issueId) {
    try {
      String endpoint = String.format("/rest/api/3/issue/%s", issueId);

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("expand", "changelog,worklog");

      return jiraRestApi.get(endpoint, params, JiraIssueResponse.class);

    } catch (Exception e) {
      log.error("이슈 ID {}의 상세 정보 조회 중 오류가 발생했습니다: {}", issueId, e.getMessage(), e);
      return null; // bulk 조회에서는 null 반환하여 해당 이슈 스킵
    }
  }

  /**
   * Jira 서버와의 연결 상태를 확인합니다.
   */
  public boolean testConnection() {
    try {
      JiraServerInfoDto serverInfo = jiraRestApi.get("/rest/api/3/serverInfo", JiraServerInfoDto.class);

      log.info("Jira 서버 연결 성공. 버전: {}, 타입: {}",
          serverInfo.getVersion(), serverInfo.getDeploymentType());
      return true;

    } catch (Exception e) {
      log.error("Jira 서버 연결 테스트 실패: {}", e.getMessage(), e);
      return false;
    }
  }

  /**
   * 특정 조건에 맞는 이슈들을 조회합니다. (Python 코드 로직 반영) 워크로그는 별도 bulk API로 완전히 조회합니다.
   */
  public List<JiraIssueDto> getFilteredIssues(String componentYear) {
    try {
      // Python 코드 로직: done 상태 또는 QA요청 상태, 특정 컴포넌트로 시작
      String jql = String.format(
          "project = %s AND (status in (Done) OR status = 'QA요청') AND component ~ '%s*' ORDER BY updated DESC",
          jiraProperties.getProjectKey(),
          componentYear
      );

      MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
      params.add("jql", jql);
      params.add("maxResults", "1000");
      params.add("expand", "changelog,worklog"); // worklog 기본 포함 (최대 20개)

      JiraSearchResultDto searchResult = jiraRestApi.get("/rest/api/3/search", params, JiraSearchResultDto.class);
      List<JiraIssueResponse> issues = searchResult.getIssues();

      if (issues == null || issues.isEmpty()) {
        log.info("필터된 이슈가 없습니다.");
        return new ArrayList<>();
      }

      // 순차처리: 필터된 이슈의 워크로그 최적화 조회를 순차적으로 처리
      List<JiraIssueDto> filteredIssues = new ArrayList<>();

      for (JiraIssueResponse issue : issues) {
        try {
          // 1. 기본 이슈 정보를 DTO로 변환 (기본 워크로그 제외)
          JiraIssueDto issueDto = convertToApplicationDtoWithoutWorklogs(issue);

          // 2. Python 최적화 로직: total > 19면 별도 API, 그렇지 않으면 기본 워크로그 사용
          List<JiraWorklogDto> worklogs = getOptimizedWorklogs(issue);
          if (worklogs != null && !worklogs.isEmpty()) {
            issueDto.setWorklogs(worklogs);
            log.debug("필터된 이슈 {}에서 {}개의 워크로그를 순차 최적화 조회했습니다.", issue.getKey(), worklogs.size());
          }

          filteredIssues.add(issueDto);

        } catch (Exception e) {
          log.error("필터된 이슈 {} 순차 처리 중 오류 발생: {}", issue.getKey(), e.getMessage());
          // 개별 이슈 실패해도 계속 처리하되, 워크로그 없이 이슈만 반환
          filteredIssues.add(convertToApplicationDtoWithoutWorklogs(issue));
        }
      }

      log.info("필터된 이슈 순차 조회 완료: {}개 이슈를 순차적으로 처리",
          filteredIssues.size());

      return filteredIssues;

    } catch (Exception e) {
      log.error("필터된 이슈 조회 중 오류가 발생했습니다: {}", e.getMessage(), e);
      throw new RuntimeException("필터된 이슈 조회 실패", e);
    }
  }

  /**
   * Infrastructure DTO를 Application DTO로 변환 (완전한 워크로그 포함)
   *
   * @deprecated bulk API 제거로 더 이상 사용하지 않음. convertToApplicationDto + getWorklogsForIssue 사용 권장
   */
  @Deprecated
  private JiraIssueDto convertToApplicationDtoWithWorklogs(
      JiraIssueResponse infraDto,
      List<JiraWorklogDto> completeWorklogs) {

    if (infraDto == null || infraDto.getFields() == null) {
      return null;
    }

    JiraIssueResponse.JiraFieldsDto fields = infraDto.getFields();

    // 완전한 워크로그 사용 (null 체크)
    List<JiraWorklogDto> worklogDtos =
        completeWorklogs != null ? completeWorklogs : new ArrayList<>();

    return JiraIssueDto.builder()
        .issueId(infraDto.getId())
        .issueKey(infraDto.getKey())
        .issueLink(infraDto.getSelf())
        .summary(fields.getSummary())
        .issueType(fields.getIssueType() != null ? fields.getIssueType().getName() : null)
        .status(fields.getStatus() != null ? fields.getStatus().getName() : null)
        .assignee(fields.getAssignee() != null ? fields.getAssignee().getDisplayName() : null)
        .components(convertComponents(fields.getComponents()))
        .storyPoints(fields.getStoryPoints() != null ? BigDecimal.valueOf(fields.getStoryPoints()) : null)
        .startDate(parseDate(fields.getStartDate()))
        .sprint(fields.getSprint())
        .worklogs(worklogDtos)
        .build();
  }

  /**
   * Infrastructure DTO를 Application DTO로 변환 (워크로그 제외)
   */
  private JiraIssueDto convertToApplicationDtoWithoutWorklogs(JiraIssueResponse infraDto) {
    if (infraDto == null || infraDto.getFields() == null) {
      return null;
    }

    JiraIssueResponse.JiraFieldsDto fields = infraDto.getFields();

    return JiraIssueDto.builder()
        .issueId(infraDto.getId())
        .issueKey(infraDto.getKey())
        .issueLink(infraDto.getSelf())
        .summary(fields.getSummary())
        .issueType(fields.getIssueType() != null ? fields.getIssueType().getName() : null)
        .status(fields.getStatus() != null ? fields.getStatus().getName() : null)
        .assignee(fields.getAssignee() != null ? fields.getAssignee().getDisplayName() : null)
        .components(convertComponents(fields.getComponents()))
        .storyPoints(fields.getStoryPoints() != null ? BigDecimal.valueOf(fields.getStoryPoints()) : null)
        .startDate(parseDate(fields.getStartDate()))
        .sprint(fields.getSprint())
        .worklogs(new ArrayList<>()) // 빈 리스트로 초기화
        .build();
  }

  /**
   * Infrastructure DTO를 Application DTO로 변환
   */
  private JiraIssueDto convertToApplicationDto(JiraIssueResponse infraDto) {
    if (infraDto == null || infraDto.getFields() == null) {
      return null;
    }

    JiraIssueResponse.JiraFieldsDto fields = infraDto.getFields();

    // worklog 정보 변환
    List<JiraWorklogDto> worklogDtos = new ArrayList<>();
    if (fields.getWorklog() != null && fields.getWorklog().getWorklogs() != null) {
      worklogDtos = fields.getWorklog().getWorklogs().stream()
          .map(worklog -> convertToApplicationWorklogDto(worklog, infraDto))
          .collect(Collectors.toList());
    }

    return JiraIssueDto.builder()
        .issueId(infraDto.getId())
        .issueKey(infraDto.getKey())
        .issueLink(infraDto.getSelf())
        .summary(fields.getSummary())
        .issueType(fields.getIssueType() != null ? fields.getIssueType().getName() : null)
        .status(fields.getStatus() != null ? fields.getStatus().getName() : null)
        .assignee(fields.getAssignee() != null ? fields.getAssignee().getDisplayName() : null)
        .components(convertComponents(fields.getComponents()))
        .storyPoints(fields.getStoryPoints() != null ? BigDecimal.valueOf(fields.getStoryPoints()) : null)
        .startDate(parseDate(fields.getStartDate()))
        .sprint(fields.getSprint())
        .worklogs(worklogDtos)
        .build();
  }

  /**
   * Infrastructure 워크로그 DTO를 Application DTO로 변환
   */
  private JiraWorklogDto convertToApplicationWorklogDto(JiraWorklogResponse infraWorklogDto, JiraIssueResponse issue) {

    if (infraWorklogDto == null) {
      return null;
    }

    JiraIssueResponse.JiraFieldsDto fields = issue.getFields();

    return JiraWorklogDto.builder()
        .id(infraWorklogDto.getId())
        .issueKey(issue.getKey())
        .issueType(fields.getIssueType() != null ? fields.getIssueType().getName() : null)
        .status(fields.getStatus() != null ? fields.getStatus().getName() : null)
        .issueLink(issue.getSelf())
        .summary(fields.getSummary())
        .author(infraWorklogDto.getAuthor() != null ? infraWorklogDto.getAuthor().getDisplayName() : "Unknown")
        .components(convertComponents(fields.getComponents()))
        .timeSpent(infraWorklogDto.getTimeSpent())
        .timeHours(infraWorklogDto.getTimeSpentSeconds() != null
            ? BigDecimal.valueOf(infraWorklogDto.getTimeSpentSeconds() / 3600.0)
            : BigDecimal.ZERO)
        .comment(infraWorklogDto.getComment() != null ? infraWorklogDto.getComment().extractText() : "")
        .started(infraWorklogDto.getStarted() != null ? infraWorklogDto.getStarted().toLocalDateTime() : null)
        .worklogId(infraWorklogDto.getSelf()) // self URL을 worklogId로 사용
        .build();
  }

  /**
   * 컴포넌트 리스트를 문자열로 변환
   */
  private String convertComponents(List<JiraIssueResponse.JiraComponentDto> components) {
    if (components == null || components.isEmpty()) {
      return null;
    }

    return components.stream()
        .map(JiraIssueResponse.JiraComponentDto::getName)
        .collect(Collectors.joining(","));
  }

  /**
   * 날짜 문자열을 LocalDate으로 변환
   */
  private LocalDate parseDate(String dateStr) {
    if (dateStr == null || dateStr.trim().isEmpty()) {
      return null;
    }

    try {
      // ISO 8601 형식으로 파싱
      return LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE);
    } catch (Exception e) {
      log.warn("날짜 파싱 실패: {}", dateStr);
      return null;
    }
  }

  /**
   * 날짜시간 문자열을 LocalDateTime으로 변환
   */
  private LocalDateTime parseDateTime(String dateTimeStr) {
    if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
      return null;
    }

    try {
      // ISO 8601 형식으로 파싱
      return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
    } catch (Exception e) {
      log.warn("날짜시간 파싱 실패: {}", dateTimeStr);
      return null;
    }
  }
}
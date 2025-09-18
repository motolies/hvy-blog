package kr.hvy.blog.modules.jira.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.core.task.TaskExecutor;
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
  private final TaskExecutor virtualThreadExecutor;

  public JiraClientWrapper(@Qualifier("jiraRestApi") RestApi jiraRestApi,
                          JiraProperties jiraProperties,
                          @Qualifier("virtualThreadExecutor") TaskExecutor virtualThreadExecutor) {
    this.jiraRestApi = jiraRestApi;
    this.jiraProperties = jiraProperties;
    this.virtualThreadExecutor = virtualThreadExecutor;
  }

  /**
   * 프로젝트의 모든 이슈를 조회합니다.
   * 새로운 cursor-based pagination을 사용하여 nextPageToken으로 페이지를 순회하며 즉시 병렬 처리합니다.
   */
  public List<JiraIssueDto> getAllIssuesFromProject() {
    try {
      String jql = String.format("project = %s ORDER BY updated DESC", jiraProperties.getProjectKey());
      int pageSize = 100;
      int maxPages = 10000; // 무한 루프 방지를 위한 최대 페이지 수 제한

      List<JiraIssueDto> allIssues = new ArrayList<>();
      String nextPageToken = null;
      int pageCount = 0;
      Set<String> seenTokens = new HashSet<>(); // 중복 토큰 체크를 위한 Set

      log.info("프로젝트 {}에서 cursor-based pagination으로 이슈를 조회합니다. (페이지 크기: {})",
          jiraProperties.getProjectKey(), pageSize);

      do {
        pageCount++;
        if (pageCount > maxPages) {
          log.warn("최대 페이지 수({})에 도달했습니다. 추가 이슈가 있을 수 있습니다.", maxPages);
          break;
        }

        log.debug("페이지 {} 조회 중... (nextPageToken: {})", pageCount,
            nextPageToken != null ? "present" : "null");

        // 중복 토큰 체크 (무한 루프 방지)
        if (nextPageToken != null && seenTokens.contains(nextPageToken)) {
          log.warn("중복된 nextPageToken이 감지되었습니다. 무한 루프를 방지하기 위해 페이지네이션을 중단합니다. Token: {}", nextPageToken);
          break;
        }
        if (nextPageToken != null) {
          seenTokens.add(nextPageToken);
        }

        // 현재 페이지 조회
        JiraSearchResultDto searchResult;
        try {
          searchResult = fetchNextPageWithCursor(jql, pageSize, nextPageToken);
        } catch (Exception e) {
          log.error("페이지 {} 조회 중 API 오류 발생: {}. 다음 페이지로 건너뜁니다.", pageCount, e.getMessage());
          break; // API 오류 시 전체 프로세스 중단
        }
        List<JiraIssueResponse> issues = searchResult.getIssues();

        if (issues == null || issues.isEmpty()) {
          log.debug("페이지 {}에서 조회된 이슈가 없습니다.", pageCount);
          break;
        }

        log.debug("페이지 {}에서 {}개 이슈 조회됨", pageCount, issues.size());

        // 현재 페이지의 이슈들을 즉시 병렬로 워크로그 처리
        final int currentPageCount = pageCount; // effectively final variable for lambda
        List<CompletableFuture<JiraIssueDto>> futures = issues.stream()
            .map(issue -> CompletableFuture.supplyAsync(() -> {
              try {
                // 1. 기본 이슈 정보를 DTO로 변환 (기본 워크로그 제외)
                JiraIssueDto issueDto = convertToApplicationDtoWithoutWorklogs(issue);

                // 2. Python 최적화 로직: total > 19면 별도 API, 그렇지 않으면 기본 워크로그 사용
                List<JiraWorklogDto> worklogs = getOptimizedWorklogs(issue);
                if (worklogs != null && !worklogs.isEmpty()) {
                  issueDto.setWorklogs(worklogs);
                  log.debug("페이지 {} 이슈 {}에서 {}개의 워크로그를 병렬 최적화 조회했습니다.",
                      currentPageCount, issue.getKey(), worklogs.size());
                }

                return issueDto;

              } catch (Exception e) {
                log.error("페이지 {} 이슈 {} 병렬 처리 중 오류 발생: {}",
                    currentPageCount, issue.getKey(), e.getMessage());
                // 개별 이슈 실패해도 계속 처리하되, 워크로그 없이 이슈만 반환
                return convertToApplicationDtoWithoutWorklogs(issue);
              }
            }, virtualThreadExecutor))
            .collect(Collectors.toList());

        // 현재 페이지의 모든 CompletableFuture 결과 수집
        for (CompletableFuture<JiraIssueDto> future : futures) {
          try {
            allIssues.add(future.get());
          } catch (Exception e) {
            log.error("페이지 {} 병렬 처리 결과 수집 중 오류 발생: {}", pageCount, e.getMessage());
          }
        }

        log.debug("페이지 {} 처리 완료: {}개 이슈를 {}개 스레드로 병렬 처리",
            pageCount, futures.size(), futures.size());

        // 다음 페이지 토큰 확인
        nextPageToken = searchResult.getNextPageToken();
        if (nextPageToken != null && nextPageToken.trim().isEmpty()) {
          nextPageToken = null; // 빈 문자열은 null로 처리
        }

      } while (nextPageToken != null);

      log.info("프로젝트 {}에서 {}개의 이슈를 성공적으로 조회했습니다. (총 {}페이지 처리)",
          jiraProperties.getProjectKey(), allIssues.size(), pageCount);

      return allIssues;

    } catch (Exception e) {
      log.error("Jira에서 cursor-based 이슈 조회 중 오류가 발생했습니다: {}", e.getMessage(), e);
      throw new RuntimeException("Jira cursor-based 이슈 조회 실패", e);
    }
  }

  /**
   * 새로운 cursor-based pagination을 사용하여 이슈 페이지를 조회합니다.
   */
  private JiraSearchResultDto fetchNextPageWithCursor(String jql, int maxResults, String nextPageToken) {
    try {
      // 새로운 JQL 검색 API 엔드포인트 사용
      String endpoint = "/rest/api/3/search/jql";

      // POST 요청을 위한 body 구성
      Map<String, Object> requestBody = new HashMap<>();
      requestBody.put("jql", jql);
      requestBody.put("maxResults", maxResults);
      requestBody.put("fields", List.of("summary", "issuetype", "assignee", "components",
          "customfield_10026", "customfield_10015", "customfield_10280", "worklog", "status"));

      if (nextPageToken != null && !nextPageToken.trim().isEmpty()) {
        requestBody.put("nextPageToken", nextPageToken);
      }

      log.debug("JQL 검색 요청: jql={}, maxResults={}, nextPageToken={}",
          jql, maxResults, nextPageToken != null ? "present" : "null");

      return jiraRestApi.post(endpoint, requestBody, JiraSearchResultDto.class);

    } catch (Exception e) {
      log.error("cursor-based 페이지 조회 중 오류 발생 (nextPageToken: {}): {}",
          nextPageToken, e.getMessage(), e);
      throw new RuntimeException("Jira cursor-based 페이지 조회 실패", e);
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

}
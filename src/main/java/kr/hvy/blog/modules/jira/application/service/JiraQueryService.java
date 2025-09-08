package kr.hvy.blog.modules.jira.application.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.hvy.blog.modules.admin.application.dto.CommonClassResponse;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeResponse;
import kr.hvy.blog.modules.admin.application.service.CommonCodePublicService;
import kr.hvy.blog.modules.jira.application.dto.JiraIssueDto;
import kr.hvy.blog.modules.jira.application.dto.JiraSprintSummaryResponseDto;
import kr.hvy.blog.modules.jira.application.dto.JiraWorklogDto;
import kr.hvy.blog.modules.jira.domain.entity.JiraIssue;
import kr.hvy.blog.modules.jira.domain.entity.JiraWorklog;
import kr.hvy.blog.modules.jira.domain.repository.JiraIssueRepository;
import kr.hvy.blog.modules.jira.domain.repository.JiraWorklogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jira 조회 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JiraQueryService {

  private final JiraIssueRepository jiraIssueRepository;
  private final JiraWorklogRepository jiraWorklogRepository;
  private final CommonCodePublicService commonCodePublicService;

  /**
   * 모든 이슈를 조회합니다.
   */
  public Page<JiraIssueDto> getAllIssues(Pageable pageable) {
    Page<JiraIssue> issuesPage = jiraIssueRepository.findAll(pageable);
    List<JiraIssueDto> issueDtos = issuesPage.getContent().stream()
        .map(this::convertToIssueDto)
        .collect(Collectors.toList());

    return new PageImpl<>(issueDtos, pageable, issuesPage.getTotalElements());
  }

  /**
   * 특정 이슈를 조회합니다.
   */
  public JiraIssueDto getIssueByKey(String issueKey) {
    JiraIssue issue = jiraIssueRepository.findByIssueKey(issueKey)
        .orElseThrow(() -> new RuntimeException("이슈를 찾을 수 없습니다: " + issueKey));

    return convertToIssueDto(issue);
  }

  /**
   * 특정 이슈의 워크로그를 조회합니다.
   */
  public List<JiraWorklogDto> getWorklogsByIssueKey(String issueKey) {
    List<JiraWorklog> worklogs = jiraWorklogRepository.findByIssueKeyOrderByStartedDesc(issueKey);

    return worklogs.stream()
        .map(this::convertToWorklogDto)
        .collect(Collectors.toList());
  }

  /**
   * 특정 기간의 워크로그를 조회합니다.
   */
  public List<JiraWorklogDto> getWorklogsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
    List<JiraWorklog> worklogs = jiraWorklogRepository
        .findByStartedBetweenOrderByStartedDesc(startDate, endDate);

    return worklogs.stream()
        .map(this::convertToWorklogDto)
        .collect(Collectors.toList());
  }

  /**
   * 작업자별 워크로그를 조회합니다.
   */
  public List<JiraWorklogDto> getWorklogsByAuthor(String author) {
    List<JiraWorklog> worklogs = jiraWorklogRepository.findByAuthorOrderByStartedDesc(author);

    return worklogs.stream()
        .map(this::convertToWorklogDto)
        .collect(Collectors.toList());
  }

  /**
   * 워크로그를 페이징으로 조회합니다.
   */
  public Page<JiraWorklogDto> getAllWorklogs(Pageable pageable) {
    Page<JiraWorklog> worklogsPage = jiraWorklogRepository.findAll(pageable);
    List<JiraWorklogDto> worklogDtos = worklogsPage.getContent().stream()
        .map(this::convertToWorklogDto)
        .collect(Collectors.toList());

    return new PageImpl<>(worklogDtos, pageable, worklogsPage.getTotalElements());
  }

  /**
   * 이슈 엔티티를 DTO로 변환합니다.
   */
  private JiraIssueDto convertToIssueDto(JiraIssue issue) {
    return JiraIssueDto.builder()
        .id(issue.getId())
        .issueKey(issue.getIssueKey())
        .issueLink(issue.getIssueLink())
        .summary(issue.getSummary())
        .issueType(issue.getIssueType())
        .status(issue.getStatus())
        .assignee(issue.getAssignee())
        .components(issue.getComponents())
        .storyPoints(issue.getStoryPoints())
        .startDate(issue.getStartDate())
        .worklogs(issue.getWorklogs().stream()
            .map(this::convertToWorklogDto)
            .collect(Collectors.toList()))
        .build();
  }

  /**
   * 특정 연도의 스프린트 서머리를 조회합니다.
   */
  public JiraSprintSummaryResponseDto getSprintSummary(String year) {
    // 1. 해당 연도의 26개 스프린트 목록 생성
    List<String> sprints = generateSprints(year);

    // 2. 해당 연도의 완료된 이슈들 조회
    String yearPattern = year + "-SP-%";

    CommonClassResponse jiraStatus = commonCodePublicService.getClass("JIRA_STATUS");
    Set<String> statuses = jiraStatus.getCodes().stream()
        .filter(code -> "Y".equals(code.getAttribute2Value()))
        .map(CommonCodeResponse::getCode)
        .collect(Collectors.toSet());

    List<JiraIssue> completedIssues = jiraIssueRepository.findCompletedIssuesByYear(yearPattern, statuses);

    // 3. 담당자별로 그룹화하여 스프린트별 스토리포인트 합계 계산
    Map<String, Map<String, BigDecimal>> assigneeSprintMap = new HashMap<>();
    Map<String, BigDecimal> sprintTotals = new LinkedHashMap<>();

    // 스프린트별 총합 초기화
    for (String sprint : sprints) {
      sprintTotals.put(sprint, BigDecimal.ZERO);
    }

    // 이슈별로 처리
    for (JiraIssue issue : completedIssues) {
      String assignee = issue.getAssignee();
      String sprint = issue.getSprint();
      BigDecimal storyPoints = issue.getStoryPoints();

      if (sprint != null && storyPoints != null && storyPoints.compareTo(BigDecimal.ZERO) > 0) {
        // 담당자별 맵 초기화
        assigneeSprintMap.computeIfAbsent(assignee, k -> {
          Map<String, BigDecimal> sprintMap = new LinkedHashMap<>();
          // 모든 스프린트를 0으로 초기화
          for (String s : sprints) {
            sprintMap.put(s, BigDecimal.ZERO);
          }
          return sprintMap;
        });

        // 해당 스프린트에 스토리포인트 추가
        if (assigneeSprintMap.get(assignee).containsKey(sprint)) {
          BigDecimal currentPoints = assigneeSprintMap.get(assignee).get(sprint);
          assigneeSprintMap.get(assignee).put(sprint, currentPoints.add(storyPoints));

          // 스프린트별 총합에도 추가
          sprintTotals.put(sprint, sprintTotals.get(sprint).add(storyPoints));
        }
      }
    }

    // 4. AssigneeSummary 리스트 생성
    List<JiraSprintSummaryResponseDto.AssigneeSummary> assigneeSummaries = new ArrayList<>();

    for (Map.Entry<String, Map<String, BigDecimal>> entry : assigneeSprintMap.entrySet()) {
      String assignee = entry.getKey();
      Map<String, BigDecimal> sprintStoryPoints = entry.getValue();

      // 해당 담당자의 연간 총 스토리포인트 계산
      BigDecimal totalStoryPoints = sprintStoryPoints.values().stream()
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      JiraSprintSummaryResponseDto.AssigneeSummary assigneeSummary =
          JiraSprintSummaryResponseDto.AssigneeSummary.builder()
              .assignee(assignee)
              .sprintStoryPoints(sprintStoryPoints)
              .totalStoryPoints(totalStoryPoints)
              .build();

      assigneeSummaries.add(assigneeSummary);
    }

    // 5. 담당자명으로 정렬
    assigneeSummaries.sort((a, b) -> a.getAssignee().compareTo(b.getAssignee()));

    // 6. 응답 DTO 생성
    return JiraSprintSummaryResponseDto.builder()
        .year(year)
        .sprints(sprints)
        .assigneeSummaries(assigneeSummaries)
        .sprintTotals(sprintTotals)
        .build();
  }

  /*
   * 특정 연도의 26개 스프린트 목록을 생성합니다.
   * 예: 2024-SP-01, 2024-SP-02, ..., 2024-SP-26
   */
  private List<String> generateSprints(String year) {
    return IntStream.rangeClosed(1, 26)
        .mapToObj(i -> String.format("%s-SP-%02d", year, i))
        .collect(Collectors.toList());
  }

  /**
   * 워크로그 엔티티를 DTO로 변환합니다.
   */
  private JiraWorklogDto convertToWorklogDto(JiraWorklog worklog) {
    return JiraWorklogDto.builder()
        .id(worklog.getId())
        .issueKey(worklog.getIssueKey())
        .issueType(worklog.getIssueType())
        .status(worklog.getStatus())
        .issueLink(worklog.getIssueLink())
        .summary(worklog.getSummary())
        .author(worklog.getAuthor())
        .components(worklog.getComponents())
        .timeSpent(worklog.getTimeSpent())
        .timeHours(worklog.getTimeHours())
        .comment(worklog.getComment())
        .started(worklog.getStarted())
        .worklogId(worklog.getWorklogId())
        .build();
  }
}
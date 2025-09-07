package kr.hvy.blog.modules.jira.application.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.jira.application.dto.JiraIssueDto;
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
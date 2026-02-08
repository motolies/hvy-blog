package kr.hvy.blog.modules.jira.application.service;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.jira.application.dto.IssueDto;
import kr.hvy.blog.modules.jira.application.dto.WorklogDto;
import org.springframework.util.CollectionUtils;
import kr.hvy.blog.modules.jira.domain.WorklogInfo;
import kr.hvy.blog.modules.jira.domain.entity.JiraIssue;
import kr.hvy.blog.modules.jira.repository.JiraIssueRepository;
import kr.hvy.blog.modules.jira.client.JiraProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jira 동기화 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraSyncService {

  private final JiraIssueRepository jiraIssueRepository;
  private final JiraProperties jiraProperties;


  /**
   * DDD 방식으로 이슈와 워크로그를 함께 동기화합니다. 이슈 애그리게이트 루트를 통해 워크로그를 관리하므로 cascade로 자동 저장됩니다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void syncIssueWithWorklogsDDD(IssueDto issueDto) {
    Optional<JiraIssue> existingIssue = jiraIssueRepository.findByIssueKey(issueDto.getIssueKey());

    JiraIssue jiraIssue;

    if (existingIssue.isPresent()) {
      // 기존 이슈 업데이트
      jiraIssue = existingIssue.get();
      updateIssueFromJira(jiraIssue, issueDto);

      // DDD: 이슈 애그리게이트를 통한 워크로그 동기화
      if (!CollectionUtils.isEmpty(issueDto.getWorklogs())) {
        List<WorklogInfo> worklogInfos = toWorklogInfoList(issueDto.getWorklogs());
        jiraIssue.syncWorklogs(worklogInfos);
        log.debug("이슈 {} - {}개 워크로그 동기화됨", issueDto.getIssueKey(),
            issueDto.getWorklogs().size());
      }
    } else {
      // 새 이슈 생성
      jiraIssue = createIssueFromJira(issueDto);

      // DDD: 새 이슈에 워크로그 추가
      if (!CollectionUtils.isEmpty(issueDto.getWorklogs())) {
        List<WorklogInfo> worklogInfos = toWorklogInfoList(issueDto.getWorklogs());
        for (WorklogInfo worklogInfo : worklogInfos) {
          jiraIssue.addWorklog(worklogInfo);
        }
        log.debug("신규 이슈 {} - {}개 워크로그 추가됨", issueDto.getIssueKey(),
            issueDto.getWorklogs().size());
      }
    }

    // 이슈만 저장하면 cascade로 워크로그도 자동 저장됨
    JiraIssue savedIssue = jiraIssueRepository.save(jiraIssue);

    log.debug("이슈 {} DDD 저장 완료. 총 워크로그: {}개",
        savedIssue.getIssueKey(), savedIssue.getWorklogCount());

  }

  /**
   * Jira 이슈 DTO로부터 엔티티를 생성합니다.
   */
  private JiraIssue createIssueFromJira(IssueDto issueDto) {
    return JiraIssue.builder()
        .jiraIssueId(issueDto.getIssueId())
        .issueKey(issueDto.getIssueKey())
        .issueLink(buildIssueLink(issueDto.getIssueKey()))
        .summary(issueDto.getSummary())
        .issueType(issueDto.getIssueType())
        .status(issueDto.getStatus())
        .assignee(issueDto.getAssignee())
        .components(issueDto.getComponents())
        .storyPoints(issueDto.getStoryPoints())
        .startDate(issueDto.getStartDate())
        .endDate(issueDto.getEndDate())
        .sprint(issueDto.getSprint())
        .build();
  }

  /**
   * 기존 이슈를 Jira DTO 데이터로 업데이트합니다.
   */
  private void updateIssueFromJira(JiraIssue jiraIssue, IssueDto issueDto) {
    jiraIssue.updateIssueInfo(
        issueDto.getSummary(),
        issueDto.getIssueType(),
        issueDto.getStatus(),
        issueDto.getAssignee(),
        issueDto.getComponents(),
        issueDto.getStoryPoints(),
        issueDto.getStartDate(),
        issueDto.getEndDate(),
        issueDto.getSprint()
    );
  }

  /**
   * 이슈 링크를 구성합니다.
   */
  private String buildIssueLink(String issueKey) {
    return String.format("%s/browse/%s", jiraProperties.getUrl(), issueKey);
  }

  /**
   * WorklogDto 리스트를 도메인 값 객체 WorklogInfo 리스트로 변환합니다.
   */
  private List<WorklogInfo> toWorklogInfoList(List<WorklogDto> worklogDtos) {
    return worklogDtos.stream()
        .map(this::toWorklogInfo)
        .toList();
  }

  /**
   * WorklogDto를 도메인 값 객체 WorklogInfo로 변환합니다.
   */
  private WorklogInfo toWorklogInfo(WorklogDto dto) {
    return WorklogInfo.builder()
        .issueKey(dto.getIssueKey())
        .issueType(dto.getIssueType())
        .status(dto.getStatus())
        .issueLink(dto.getIssueLink())
        .summary(dto.getSummary())
        .author(dto.getAuthor())
        .components(dto.getComponents())
        .timeSpent(dto.getTimeSpent())
        .timeHours(dto.getTimeHours())
        .comment(dto.getComment())
        .started(dto.getStarted())
        .worklogId(dto.getWorklogId())
        .build();
  }

}
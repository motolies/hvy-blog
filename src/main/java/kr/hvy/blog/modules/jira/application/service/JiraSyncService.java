package kr.hvy.blog.modules.jira.application.service;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.jira.application.dto.JiraIssueDto;
import kr.hvy.blog.modules.jira.application.dto.JiraWorklogDto;
import kr.hvy.blog.modules.jira.domain.entity.JiraIssue;
import kr.hvy.blog.modules.jira.domain.repository.JiraIssueRepository;
import kr.hvy.blog.modules.jira.infrastructure.client.JiraClientWrapper;
import kr.hvy.blog.modules.jira.infrastructure.config.JiraProperties;
import kr.hvy.common.infrastructure.redis.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jira 동기화 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraSyncService {

  private final JiraClientWrapper jiraClientWrapper;
  private final JiraIssueRepository jiraIssueRepository;
  private final JiraProperties jiraProperties;

  /**
   * 프로젝트의 모든 이슈와 워크로그를 동기화합니다. DDD 방식으로 이슈 애그리게이트를 통해 워크로그를 함께 처리합니다.
   */
  @DistributedLock(key = "JIRA_SYNC", leaseTime = 600)
  public void syncAllIssuesAndWorklogs() {
    log.info("Jira 이슈 및 워크로그 동기화를 시작합니다.");

    try {
      List<JiraIssueDto> issues = jiraClientWrapper.getAllIssuesFromProject();
      log.info("{}개의 이슈와 총 {}개의 워크로그를 DDD 방식 배치 동기화 시작합니다.",
          issues.size(),
          issues.stream().mapToInt(issue -> issue.getWorklogs() != null ? issue.getWorklogs().size() : 0).sum());

      // DDD 방식으로 배치 처리
      syncIssuesWithWorklogsBatch(issues);

    } catch (Exception e) {
      log.error("Jira 동기화 중 오류가 발생했습니다: {}", e.getMessage(), e);
      throw new RuntimeException("Jira 동기화 실패", e);
    }
  }

  /**
   * 이슈 리스트와 포함된 워크로그들을 DDD 방식으로 동기화합니다. 이슈 애그리게이트 루트를 통해 워크로그를 관리합니다.
   */
  @Transactional
  public void syncIssuesWithWorklogsBatch(List<JiraIssueDto> issueDtos) {
    int syncedIssues = 0;
    int syncedWorklogs = 0;
    int failedIssues = 0;

    log.info("{}개 이슈의 DDD 방식 배치 동기화를 시작합니다.", issueDtos.size());

    for (JiraIssueDto issueDto : issueDtos) {
      try {
        // DDD 방식: 이슈 애그리게이트 루트를 통한 동기화
        syncIssueWithWorklogsDDD(issueDto);
        syncedIssues++;

        // 워크로그 개수 계산
        int worklogCount = issueDto.getWorklogs() != null ? issueDto.getWorklogs().size() : 0;
        syncedWorklogs += worklogCount;

        log.debug("이슈 {} DDD 동기화 완료. 워크로그: {}개", issueDto.getIssueKey(), worklogCount);

      } catch (Exception e) {
        failedIssues++;
        log.error("이슈 {} DDD 동기화 중 오류 발생: {}", issueDto.getIssueKey(), e.getMessage(), e);
        // 개별 이슈 실패해도 다음 이슈는 계속 처리
      }
    }

    log.info("DDD 배치 동기화 완료. 이슈: {}개 성공, {}개 실패, 워크로그: {}개 처리",
        syncedIssues, failedIssues, syncedWorklogs);
  }

  /**
   * DDD 방식으로 이슈와 워크로그를 함께 동기화합니다. 이슈 애그리게이트 루트를 통해 워크로그를 관리하므로 cascade로 자동 저장됩니다.
   */
  @Transactional
  public JiraIssue syncIssueWithWorklogsDDD(JiraIssueDto issueDto) {
    Optional<JiraIssue> existingIssue = jiraIssueRepository.findByIssueKey(issueDto.getIssueKey());

    JiraIssue jiraIssue;

    if (existingIssue.isPresent()) {
      // 기존 이슈 업데이트
      jiraIssue = existingIssue.get();
      updateIssueFromJira(jiraIssue, issueDto);

      // DDD: 이슈 애그리게이트를 통한 워크로그 동기화
      if (issueDto.getWorklogs() != null && !issueDto.getWorklogs().isEmpty()) {
        jiraIssue.syncWorklogs(issueDto.getWorklogs());
        log.debug("이슈 {} - {}개 워크로그 동기화됨", issueDto.getIssueKey(),
            issueDto.getWorklogs().size());
      }
    } else {
      // 새 이슈 생성
      jiraIssue = createIssueFromJira(issueDto);

      // DDD: 새 이슈에 워크로그 추가
      if (issueDto.getWorklogs() != null && !issueDto.getWorklogs().isEmpty()) {
        for (JiraWorklogDto worklogDto : issueDto.getWorklogs()) {
          jiraIssue.addWorklog(worklogDto);
        }
        log.debug("신규 이슈 {} - {}개 워크로그 추가됨", issueDto.getIssueKey(),
            issueDto.getWorklogs().size());
      }
    }

    // 이슈만 저장하면 cascade로 워크로그도 자동 저장됨
    JiraIssue savedIssue = jiraIssueRepository.save(jiraIssue);

    log.debug("이슈 {} DDD 저장 완료. 총 워크로그: {}개",
        savedIssue.getIssueKey(), savedIssue.getWorklogCount());

    return savedIssue;
  }

  /**
   * Jira 이슈 DTO로부터 엔티티를 생성합니다.
   */
  private JiraIssue createIssueFromJira(JiraIssueDto issueDto) {
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
        .sprint(issueDto.getSprint())
        .build();
  }

  /**
   * 기존 이슈를 Jira DTO 데이터로 업데이트합니다.
   */
  private void updateIssueFromJira(JiraIssue jiraIssue, JiraIssueDto issueDto) {
    jiraIssue.updateIssueInfo(
        issueDto.getSummary(),
        issueDto.getIssueType(),
        issueDto.getStatus(),
        issueDto.getAssignee(),
        issueDto.getComponents(),
        issueDto.getStoryPoints(),
        issueDto.getStartDate(),
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
   * 연결 테스트
   */
  public boolean testConnection() {
    return jiraClientWrapper.testConnection();
  }
}
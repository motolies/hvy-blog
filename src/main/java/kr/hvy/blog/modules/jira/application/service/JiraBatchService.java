package kr.hvy.blog.modules.jira.application.service;

import java.util.List;
import kr.hvy.blog.modules.jira.application.dto.JiraIssueDto;
import kr.hvy.blog.modules.jira.infrastructure.client.JiraClientWrapper;
import kr.hvy.common.infrastructure.redis.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Jira 동기화 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraBatchService {

  private final JiraClientWrapper jiraClientWrapper;
  private final JiraSyncService jiraSyncService;

  /**
   * 프로젝트의 모든 이슈와 워크로그를 동기화합니다. DDD 방식으로 이슈 애그리게이트를 통해 워크로그를 함께 처리합니다.
   */
  @Async
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
  public void syncIssuesWithWorklogsBatch(List<JiraIssueDto> issueDtos) {
    int syncedIssues = 0;
    int syncedWorklogs = 0;
    int failedIssues = 0;

    log.info("{}개 이슈의 DDD 방식 배치 동기화를 시작합니다.", issueDtos.size());

    for (JiraIssueDto issueDto : issueDtos) {
      try {
        // DDD 방식: 이슈 애그리게이트 루트를 통한 동기화
        jiraSyncService.syncIssueWithWorklogsDDD(issueDto);
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

}
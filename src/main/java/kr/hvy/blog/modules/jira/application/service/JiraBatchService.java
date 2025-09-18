package kr.hvy.blog.modules.jira.application.service;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import kr.hvy.blog.modules.jira.application.dto.JiraIssueDto;
import kr.hvy.blog.modules.jira.infrastructure.client.JiraClientWrapper;
import kr.hvy.common.infrastructure.redis.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
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
  private final TaskExecutor virtualThreadExecutor;


  @Value("${jira.sync.batch-size:50}")
  private int batchSize;

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
   * 이슈 리스트와 포함된 워크로그들을 DDD 방식으로 VirtualThreads를 활용하여 배치 단위로 병렬 동기화합니다. 이슈 애그리게이트 루트를 통해 워크로그를 관리합니다.
   */
  public void syncIssuesWithWorklogsBatch(List<JiraIssueDto> issueDtos) {
    AtomicInteger syncedIssues = new AtomicInteger(0);
    AtomicInteger syncedWorklogs = new AtomicInteger(0);
    AtomicInteger failedIssues = new AtomicInteger(0);

    log.info("{}개 이슈의 DDD 방식 VirtualThreads 병렬 배치 동기화를 시작합니다. 배치 크기: {}",
        issueDtos.size(), batchSize);

    List<List<JiraIssueDto>> batches = Lists.partition(issueDtos, batchSize);

    batches.stream()
        .map(batch -> processBatch(batch, syncedIssues, syncedWorklogs, failedIssues,
            batches.indexOf(batch) + 1, batches.size()))
        .forEach(CompletableFuture::join);

    log.info("DDD VirtualThreads 병렬 배치 동기화 완료. 총 이슈: {}개 성공, {}개 실패, 워크로그: {}개 처리",
        syncedIssues.get(), failedIssues.get(), syncedWorklogs.get());
  }

  /**
   * 개별 배치를 처리하는 메서드
   */
  private CompletableFuture<Void> processBatch(List<JiraIssueDto> batchIssues,
                                               AtomicInteger syncedIssues,
                                               AtomicInteger syncedWorklogs,
                                               AtomicInteger failedIssues,
                                               int batchNumber,
                                               int totalBatches) {

    log.info("배치 {}/{} 처리 중 ({}개 이슈)", batchNumber, totalBatches, batchIssues.size());

    // 배치 내에서 병렬 처리
    List<CompletableFuture<SyncResult>> futures = batchIssues.stream()
        .map(issueDto -> CompletableFuture.supplyAsync(() -> {
          try {
            // DDD 방식: 이슈 애그리게이트 루트를 통한 동기화
            jiraSyncService.syncIssueWithWorklogsDDD(issueDto);

            int worklogCount = issueDto.getWorklogs() != null ? issueDto.getWorklogs().size() : 0;

            log.debug("이슈 {} DDD 동기화 완료. 워크로그: {}개", issueDto.getIssueKey(), worklogCount);

            return new SyncResult(true, worklogCount);

          } catch (Exception e) {
            log.error("이슈 {} DDD 동기화 중 오류 발생: {}", issueDto.getIssueKey(), e.getMessage(), e);
            return new SyncResult(false, 0);
          }
        }, virtualThreadExecutor))
        .toList();

    // 현재 배치의 모든 작업 완료 대기 및 결과 집계
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenRun(() -> {
          futures.forEach(future -> {
            try {
              SyncResult result = future.get();
              if (result.success()) {
                syncedIssues.incrementAndGet();
                syncedWorklogs.addAndGet(result.worklogCount());
              } else {
                failedIssues.incrementAndGet();
              }
            } catch (Exception e) {
              log.error("결과 집계 중 오류 발생: {}", e.getMessage(), e);
              failedIssues.incrementAndGet();
            }
          });

          log.info("배치 {}/{} 완료. 현재까지 이슈: {}개 성공, {}개 실패, 워크로그: {}개 처리",
              batchNumber, totalBatches, syncedIssues.get(), failedIssues.get(), syncedWorklogs.get());
        });
  }

  /**
   * 동기화 결과를 담는 레코드
   */
  private record SyncResult(boolean success, int worklogCount) {

  }

}
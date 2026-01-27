package kr.hvy.blog.modules.jira.application.service;

import com.google.common.collect.Lists;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.admin.application.dto.CommonClassResponse;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeResponse;
import kr.hvy.blog.modules.admin.application.service.CommonCodePublicService;
import kr.hvy.blog.modules.jira.application.dto.IssueDto;
import kr.hvy.blog.modules.jira.domain.repository.JiraIssueRepository;
import kr.hvy.blog.modules.jira.infrastructure.client.JiraClientWrapper;
import kr.hvy.blog.modules.jira.infrastructure.client.JiraClientWrapper.PageResult;
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
  private final JiraIssueRepository jiraIssueRepository;
  private final CommonCodePublicService commonCodePublicService;
  private final TaskExecutor virtualThreadExecutor;


  @Value("${jira.sync.batch-size:50}")
  private int batchSize;

  /**
   * 프로젝트의 모든 이슈와 워크로그를 동기화합니다. DDD 방식으로 이슈 애그리게이트를 통해 워크로그를 함께 처리합니다.
   * 스트리밍 방식으로 페이지 단위 처리하여 메모리 효율을 높입니다.
   * endDate가 설정된 완료된 이슈는 동기화 대상에서 제외됩니다.
   */
  @Async
  @DistributedLock(key = "JIRA_SYNC", leaseTime = 600)
  public void syncAllIssuesAndWorklogs() {
    log.info("Jira 이슈 및 워크로그 동기화를 시작합니다. (스트리밍 방식)");

    try {
      // Done 상태 목록 조회 (JIRA_STATUS 공통코드에서 attribute2Value='Y'인 상태)
      Set<String> doneStatuses = getDoneStatuses();
      log.info("Done 상태 목록: {}", doneStatuses);

      // 이미 완료된 이슈(endDate가 있는 이슈) 키 목록 조회
      Set<String> completedIssueKeys = jiraIssueRepository.findCompletedIssueKeys();
      log.info("이미 완료된 이슈 수: {}개", completedIssueKeys.size());

      // 동기화 통계
      AtomicInteger totalSynced = new AtomicInteger(0);
      AtomicInteger totalSkipped = new AtomicInteger(0);
      AtomicInteger totalWorklogs = new AtomicInteger(0);

      // 스트리밍 방식으로 페이지 단위 처리 (메모리 효율 향상)
      jiraClientWrapper.streamAllIssuesFromProject(pageResult -> {
        // 완료된 이슈 제외
        List<IssueDto> issuesToSync = pageResult.getIssues().stream()
            .filter(issue -> !completedIssueKeys.contains(issue.getIssueKey()))
            .collect(Collectors.toList());

        int skipped = pageResult.getIssues().size() - issuesToSync.size();
        totalSkipped.addAndGet(skipped);

        if (issuesToSync.isEmpty()) {
          log.debug("페이지 {}: 동기화 대상 없음 ({}개 스킵)", pageResult.getPageNumber(), skipped);
          return;
        }

        // 페이지 단위 배치 처리 (DB 기반 changelog 필터링 적용)
        int worklogCount = syncPageWithChangelogOptimization(issuesToSync, doneStatuses);

        totalSynced.addAndGet(issuesToSync.size());
        totalWorklogs.addAndGet(worklogCount);

        log.info("페이지 {} 처리 완료: {}개 동기화, {}개 스킵, {}개 워크로그",
            pageResult.getPageNumber(), issuesToSync.size(), skipped, worklogCount);
      });

      log.info("Jira 동기화 완료. 총 {}개 이슈, {}개 워크로그 처리 ({}개 스킵)",
          totalSynced.get(), totalWorklogs.get(), totalSkipped.get());

    } catch (Exception e) {
      log.error("Jira 동기화 중 오류가 발생했습니다: {}", e.getMessage(), e);
      throw new RuntimeException("Jira 동기화 실패", e);
    }
  }

  /**
   * 페이지 단위로 이슈를 동기화합니다.
   * DB에서 endDate를 조회하여 changelog API 호출을 최소화합니다.
   *
   * @param issues       동기화할 이슈 목록
   * @param doneStatuses Done 상태 목록
   * @return 처리된 워크로그 수
   */
  private int syncPageWithChangelogOptimization(List<IssueDto> issues, Set<String> doneStatuses) {
    // 현재 페이지 이슈 키 목록
    Set<String> issueKeys = issues.stream()
        .map(IssueDto::getIssueKey)
        .collect(Collectors.toSet());

    // DB에서 endDate가 있는 이슈들 조회 (Map으로 변환)
    Map<String, LocalDate> existingEndDates = new HashMap<>();
    List<Object[]> endDateResults = jiraIssueRepository.findEndDatesByIssueKeys(issueKeys);
    for (Object[] result : endDateResults) {
      existingEndDates.put((String) result[0], (LocalDate) result[1]);
    }

    log.debug("페이지 {}개 이슈 중 DB에 endDate 있는 이슈: {}개",
        issues.size(), existingEndDates.size());

    AtomicInteger worklogCount = new AtomicInteger(0);
    AtomicInteger changelogApiCalls = new AtomicInteger(0);

    // 병렬 처리
    List<CompletableFuture<Void>> futures = issues.stream()
        .map(issueDto -> CompletableFuture.runAsync(() -> {
          try {
            // Done 상태이고 DB에도 Jira에도 endDate가 없는 경우에만 changelog API 호출
            if (doneStatuses.contains(issueDto.getStatus()) && issueDto.getEndDate() == null) {
              // DB에서 endDate 확인
              LocalDate existingEndDate = existingEndDates.get(issueDto.getIssueKey());
              if (existingEndDate != null) {
                // DB에 이미 있으면 API 호출 없이 사용
                issueDto.setEndDate(existingEndDate);
                log.debug("이슈 {} endDate DB에서 조회: {}", issueDto.getIssueKey(), existingEndDate);
              } else {
                // DB에도 없으면 changelog API 호출
                LocalDate endDate = jiraClientWrapper.getEndDateFromChangelog(issueDto.getIssueKey(), doneStatuses);
                issueDto.setEndDate(endDate);
                changelogApiCalls.incrementAndGet();
                if (endDate != null) {
                  log.debug("이슈 {} endDate changelog에서 조회: {}", issueDto.getIssueKey(), endDate);
                }
              }
            }

            // DDD 방식 동기화
            jiraSyncService.syncIssueWithWorklogsDDD(issueDto);

            int wc = issueDto.getWorklogs() != null ? issueDto.getWorklogs().size() : 0;
            worklogCount.addAndGet(wc);

          } catch (Exception e) {
            log.error("이슈 {} 동기화 중 오류: {}", issueDto.getIssueKey(), e.getMessage(), e);
          }
        }, virtualThreadExecutor))
        .toList();

    // 모든 작업 완료 대기
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    if (changelogApiCalls.get() > 0) {
      log.debug("changelog API 호출 수: {}개 (DB 조회로 {}개 절약)",
          changelogApiCalls.get(), existingEndDates.size());
    }

    return worklogCount.get();
  }

  /**
   * JIRA_STATUS 공통코드에서 Done 상태 목록 조회
   * attribute2Value가 'Y'인 상태를 Done 상태로 간주
   */
  private Set<String> getDoneStatuses() {
    CommonClassResponse jiraStatus = commonCodePublicService.getClass("JIRA_STATUS");
    return jiraStatus.getCodes().stream()
        .filter(code -> "Y".equals(code.getAttribute2Value()))
        .map(CommonCodeResponse::getCode)
        .collect(Collectors.toSet());
  }

  /**
   * 이슈 리스트와 포함된 워크로그들을 DDD 방식으로 VirtualThreads를 활용하여 배치 단위로 병렬 동기화합니다. 이슈 애그리게이트 루트를 통해 워크로그를 관리합니다.
   */
  public void syncIssuesWithWorklogsBatch(List<IssueDto> issueDtos, Set<String> doneStatuses) {
    AtomicInteger syncedIssues = new AtomicInteger(0);
    AtomicInteger syncedWorklogs = new AtomicInteger(0);
    AtomicInteger failedIssues = new AtomicInteger(0);

    log.info("{}개 이슈의 DDD 방식 VirtualThreads 병렬 배치 동기화를 시작합니다. 배치 크기: {}",
        issueDtos.size(), batchSize);

    List<List<IssueDto>> batches = Lists.partition(issueDtos, batchSize);

    batches.stream()
        .map(batch -> processBatch(batch, syncedIssues, syncedWorklogs, failedIssues,
            batches.indexOf(batch) + 1, batches.size(), doneStatuses))
        .forEach(CompletableFuture::join);

    log.info("DDD VirtualThreads 병렬 배치 동기화 완료. 총 이슈: {}개 성공, {}개 실패, 워크로그: {}개 처리",
        syncedIssues.get(), failedIssues.get(), syncedWorklogs.get());
  }

  /**
   * 개별 배치를 처리하는 메서드
   */
  private CompletableFuture<Void> processBatch(List<IssueDto> batchIssues,
                                               AtomicInteger syncedIssues,
                                               AtomicInteger syncedWorklogs,
                                               AtomicInteger failedIssues,
                                               int batchNumber,
                                               int totalBatches,
                                               Set<String> doneStatuses) {

    log.info("배치 {}/{} 처리 중 ({}개 이슈)", batchNumber, totalBatches, batchIssues.size());

    // 배치 내에서 병렬 처리
    List<CompletableFuture<SyncResult>> futures = batchIssues.stream()
        .map(issueDto -> CompletableFuture.supplyAsync(() -> {
          try {
            // Done 상태인 경우 changelog에서 endDate 조회
            if (doneStatuses.contains(issueDto.getStatus()) && issueDto.getEndDate() == null) {
              LocalDate endDate = jiraClientWrapper.getEndDateFromChangelog(issueDto.getIssueKey(), doneStatuses);
              issueDto.setEndDate(endDate);
              if (endDate != null) {
                log.debug("이슈 {} Done 상태 감지. endDate 설정: {}", issueDto.getIssueKey(), endDate);
              }
            }

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
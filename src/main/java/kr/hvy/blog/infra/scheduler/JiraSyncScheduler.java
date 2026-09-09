package kr.hvy.blog.infra.scheduler;

import kr.hvy.blog.modules.jira.application.service.JiraBatchService;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Jira 동기화 스케줄러
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.jira.enabled", havingValue = "true", matchIfMissing = true)
public class JiraSyncScheduler extends AbstractScheduler {

  private final JiraBatchService jiraBatchService;

  /**
   * 10분마다 Jira 이슈 및 워크로그 동기화 실행
   */
  @Scheduled(cron = "${scheduler.jira.cron-expression:0 */10 * * * ?}", zone = "UTC")
  @SchedulerLock(name = "${scheduler.jira.lock-name:JIRA-SYNC}", lockAtMostFor = "9m", lockAtLeastFor = "1m")
  public void syncJiraData() {
    proceedScheduler("JIRA-ISSUE-COLLECTION")
        .accept(this::jiraIssueCollection);
  }

  /**
   * 동기화를 스케줄러 스레드에서 <b>동기로</b> 실행한다.
   * <p>
   * 비동기 진입점({@code JiraSyncAsyncLauncher})을 쓰면 즉시 리턴해 {@code proceedScheduler} 의 트레이스
   * 경계와 소요시간 측정이 실작업 전에 닫히고, ShedLock 도 작업이 끝나기 전에 풀린다.
   */
  private void jiraIssueCollection() {

    try {
      // 데이터 동기화 실행
      jiraBatchService.syncAllIssuesAndWorklogs();
    } catch (Exception e) {
      log.error("Jira 데이터 동기화 스케줄러 실행 중 오류 발생: {}", e.getMessage(), e);
    }
  }

}
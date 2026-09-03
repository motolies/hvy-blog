package kr.hvy.blog.infra.scheduler;

import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.application.service.StockCollectOrchestrator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기업행사·수정계수·재무 주간 수집 (일요일 03:00 KST).
 * <p>
 * run 생성·중복 차단·카운터·Slack 은 오케스트레이터가 처리한다. 같은 잡이 이미 RUNNING 이면
 * CollectAlreadyRunningException 이 나고 proceedScheduler 가 로그로 삼킨다. 백필 완료 전까지 yml enabled:false.
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.stock-weekly.enabled", havingValue = "true")
public class StockWeeklyScheduler extends AbstractScheduler {

  private final StockCollectOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.stock-weekly.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-weekly.lock-name:STOCK-WEEKLY}", lockAtMostFor = "2h", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("STOCK-WEEKLY")
        .accept(() -> {
          orchestrator.trigger(CollectJobType.WEEKLY, BackfillRequest.empty(), TriggerType.SCHEDULER);
        });
  }
}

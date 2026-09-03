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
 * MASTER 갱신 후 휴장일 1페이지 수집 (평일 05:30 KST).
 * <p>
 * run 생성·중복 차단·카운터·Slack 은 오케스트레이터가 처리한다. 같은 잡이 이미 RUNNING 이면
 * CollectAlreadyRunningException 이 나고 proceedScheduler 가 로그로 삼킨다. 백필 완료 전까지 yml enabled:false.
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.stock-master.enabled", havingValue = "true")
public class StockMasterScheduler extends AbstractScheduler {

  private final StockCollectOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.stock-master.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-master.lock-name:STOCK-MASTER}", lockAtMostFor = "15m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("STOCK-MASTER")
        .accept(() -> {
          orchestrator.trigger(CollectJobType.MASTER, BackfillRequest.empty(), TriggerType.SCHEDULER);
          orchestrator.trigger(CollectJobType.HOLIDAY, BackfillRequest.empty(), TriggerType.SCHEDULER);
        });
  }
}

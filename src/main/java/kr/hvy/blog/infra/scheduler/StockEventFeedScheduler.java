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
 * 사건 피드(GDELT) 수집 — 아침(화~토 06:40, 완결된 어제 UTC 일자 시계열)과 저녁(평일 19:20, advisor 19:30 판단 직전 헤드라인). 둘 다 같은 NEWS 잡이다.
 * 테마 6 × 모드 3 = 18 호출에 호출 간격 6초라 한 번에 2~3분 걸린다. yml scheduler.stock-eventfeed.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.stock-eventfeed.enabled", havingValue = "true")
public class StockEventFeedScheduler extends AbstractScheduler {

  private final StockCollectOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.stock-eventfeed.cron-am}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-eventfeed.lock-name-am:STOCK-EVENTFEED-AM}", lockAtMostFor = "15m", lockAtLeastFor = "1m")
  public void morning() {
    proceedScheduler("STOCK-EVENTFEED-AM")
        .accept(() -> orchestrator.trigger(CollectJobType.NEWS, BackfillRequest.empty(), TriggerType.SCHEDULER));
  }

  @Scheduled(cron = "${scheduler.stock-eventfeed.cron-pm}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-eventfeed.lock-name-pm:STOCK-EVENTFEED-PM}", lockAtMostFor = "15m", lockAtLeastFor = "1m")
  public void evening() {
    proceedScheduler("STOCK-EVENTFEED-PM")
        .accept(() -> orchestrator.trigger(CollectJobType.NEWS, BackfillRequest.empty(), TriggerType.SCHEDULER));
  }
}

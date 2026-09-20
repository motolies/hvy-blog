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
 * 거시 위험 지표(VIX·미국 국채 수익률) 증분 수집 (화~토 06:35·08:35 KST). 미국 마감(05:00 KST) 뒤 CBOE·재무부가 당일 값을 올리는 시각이
 * 겨울엔 06:35 를 넘길 수 있어 08:35 에 한 번 더 돈다(upsert 라 중복 무해). yml scheduler.stock-macro.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.stock-macro.enabled", havingValue = "true")
public class StockMacroScheduler extends AbstractScheduler {

  private final StockCollectOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.stock-macro.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-macro.lock-name:STOCK-MACRO}", lockAtMostFor = "10m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("STOCK-MACRO")
        .accept(() -> orchestrator.trigger(CollectJobType.MACRO, BackfillRequest.empty(), TriggerType.SCHEDULER));
  }
}

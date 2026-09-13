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
 * 뉴스 제목 수집 (평일 08:05~19:35 30분 간격 KST). 종합 시황/공시 제목을 tb_stock_news 에 쌓아 advisor 판단(19:30)의 근거 입력으로 쓴다.
 * yml scheduler.stock-news.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.stock-news.enabled", havingValue = "true")
public class StockNewsScheduler extends AbstractScheduler {

  private final StockCollectOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.stock-news.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-news.lock-name:STOCK-NEWS}", lockAtMostFor = "10m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("STOCK-NEWS")
        .accept(() -> orchestrator.trigger(CollectJobType.NEWS, BackfillRequest.empty(), TriggerType.SCHEDULER));
  }
}

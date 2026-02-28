package kr.hvy.blog.infra.scheduler;

import kr.hvy.blog.modules.hotdeal.application.service.HotDealService;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 핫딜 스케줄러.
 * 10분마다 등록된 사이트를 순회하며 핫딜 게시글을 스크래핑하고 Slack 알림 전송.
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.hotdeal.enabled", havingValue = "true", matchIfMissing = true)
public class HotDealScheduler extends AbstractScheduler {

  private final HotDealService hotDealService;

  @Scheduled(cron = "${scheduler.hotdeal.cron-expression:0 */10 * * * ?}")
  @SchedulerLock(name = "${scheduler.hotdeal.lock-name:HOTDEAL-SYNC}", lockAtMostFor = "9m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("HOTDEAL-SCRAPE").accept(this::scrape);
  }

  private void scrape() {
    hotDealService.scrapeAndNotify();
  }
}

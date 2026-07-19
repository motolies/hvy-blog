package kr.hvy.blog.infra.scheduler;

import java.time.Duration;
import java.time.Instant;
import kr.hvy.blog.modules.hotdeal.application.service.HotDealService;
import kr.hvy.common.aop.logging.service.ApiLogService;
import kr.hvy.common.aop.logging.service.SystemLogService;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!default")
public class LogCleanerScheduler extends AbstractScheduler {

  private final ApiLogService apiLogService;
  private final SystemLogService systemLogService;
  private final HotDealService hotDealService;

  private static final int LOG_RETENTION_DAYS = 60;
  private static final int HOTDEAL_RETENTION_DAYS = 90;

  @Scheduled(cron = "${scheduler.log-cleaner.cron-expression}", zone = "UTC")
  @SchedulerLock(name = "${scheduler.log-cleaner.lock-name}", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
  public void cleanLogs() {
    proceedScheduler("LOG-CLEANER")
        .accept(this::deleteOldData);
  }

  private void deleteOldData() {
    Instant logCutoffDate = Instant.now().minus(Duration.ofDays(LOG_RETENTION_DAYS));

    // ApiLogEntity 삭제
    int deletedApiLogs = apiLogService.deleteLogsOlderThan(logCutoffDate);

    // SystemLogEntity 삭제
    int deletedSystemLogs = systemLogService.deleteLogsOlderThan(logCutoffDate);

    log.info("로그 정리 완료: {}일 이전 로그 삭제 (API 로그: {}, 시스템 로그: {})",
        LOG_RETENTION_DAYS, deletedApiLogs, deletedSystemLogs);

    // HotDealItem 삭제
    Instant hotDealCutoffDate = Instant.now().minus(Duration.ofDays(HOTDEAL_RETENTION_DAYS));
    int deletedHotDeals = hotDealService.deleteItemsOlderThan(hotDealCutoffDate);

    log.info("핫딜 정리 완료: {}일 이전 아이템 삭제 (삭제 건수: {})",
        HOTDEAL_RETENTION_DAYS, deletedHotDeals);
  }
}

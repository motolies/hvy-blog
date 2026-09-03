package kr.hvy.blog.infra.scheduler;

import java.time.Duration;
import java.time.Instant;
import kr.hvy.blog.modules.hotdeal.application.service.HotDealService;
import kr.hvy.blog.modules.stock.application.service.StockRetentionService;
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
  private final StockRetentionService stockRetentionService;

  private static final int LOG_RETENTION_DAYS = 60;
  private static final int HOTDEAL_RETENTION_DAYS = 90;
  private static final int KIS_FAILURE_RETENTION_DAYS = 90;
  private static final int STOCK_RUN_RETENTION_DAYS = 365;

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

    // 주식 수집 운영 테이블: KIS 실패 기록 90일, 종료된 run 1년 (시계열 정본은 삭제하지 않는다)
    int deletedFailures = stockRetentionService.deleteFailuresOlderThan(
        Instant.now().minus(Duration.ofDays(KIS_FAILURE_RETENTION_DAYS)));
    int deletedRuns = stockRetentionService.deleteFinishedRunsOlderThan(
        Instant.now().minus(Duration.ofDays(STOCK_RUN_RETENTION_DAYS)));
    log.info("주식 수집 기록 정리 완료: KIS 실패 {}건({}일), run {}건({}일)",
        deletedFailures, KIS_FAILURE_RETENTION_DAYS, deletedRuns, STOCK_RUN_RETENTION_DAYS);
  }
}

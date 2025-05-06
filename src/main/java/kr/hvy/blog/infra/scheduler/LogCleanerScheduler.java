package kr.hvy.blog.infra.scheduler;

import java.time.LocalDateTime;
import kr.hvy.common.aop.log.service.ApiLogService;
import kr.hvy.common.aop.log.service.SystemLogService;
import kr.hvy.common.scheduler.AbstractScheduler;
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

  private static final int LOG_RETENTION_DAYS = 60;

  @Scheduled(cron = "${scheduler.log-cleaner.cron-expression}")
  @SchedulerLock(name = "${scheduler.log-cleaner.lock-name}", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
  public void cleanLogs() {
    proceedScheduler("LOG-CLEANER")
        .accept(this::deleteOldLogs);
  }

  private void deleteOldLogs() {
    LocalDateTime cutoffDate = LocalDateTime.now().minusDays(LOG_RETENTION_DAYS);

    // ApiLogEntity 삭제
    int deletedApiLogs = apiLogService.deleteLogsOlderThan(cutoffDate);

    // SystemLogEntity 삭제
    int deletedSystemLogs = systemLogService.deleteLogsOlderThan(cutoffDate);

    log.info("로그 정리 완료: {}일 이전 로그 삭제 (API 로그: {}, 시스템 로그: {})",
        LOG_RETENTION_DAYS, deletedApiLogs, deletedSystemLogs);
  }
}

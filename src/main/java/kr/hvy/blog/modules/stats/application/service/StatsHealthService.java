package kr.hvy.blog.modules.stats.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.stats.application.dto.ErrorSummary;
import kr.hvy.blog.modules.stats.application.dto.HealthStats;
import kr.hvy.blog.modules.stats.application.dto.SchedulerHealthState;
import kr.hvy.blog.modules.stats.application.dto.SchedulerLockRow;
import kr.hvy.blog.modules.stats.application.dto.SchedulerStatus;
import kr.hvy.blog.modules.stats.application.service.SchedulerCatalog.ResolvedJob;
import kr.hvy.blog.modules.stats.repository.mapper.StatsLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이상 징후 집계.
 * <p>
 * 캐시하지 않는다 — 이 위젯의 존재 이유가 신선도다. 게다가 집계 창이 {@code now - 24h} 라
 * 매 요청 경계가 달라져 캐시해도 100% miss 다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsHealthService {

  private static final int RECENT_ERROR_LIMIT = 5;
  private static final int SLOW_ENDPOINT_LIMIT = 5;
  private static final int SLOW_ENDPOINT_MIN_SAMPLES = 5;
  private static final int EXTERNAL_API_LIMIT = 5;

  private final StatsLogMapper statsLogMapper;
  private final SchedulerCatalog schedulerCatalog;

  public HealthStats getHealth(int hours) {
    int windowHours = Math.max(1, hours);
    Instant now = Instant.now();
    Instant recentFrom = now.minus(Duration.ofHours(windowHours));
    Instant previousFrom = recentFrom.minus(Duration.ofHours(windowHours));

    ErrorSummary summary = statsLogMapper.findErrorSummary(previousFrom, recentFrom, now);

    return HealthStats.builder()
        .windowFrom(recentFrom)
        .windowTo(now)
        .windowHours(windowHours)
        .recentRequestCount(summary.getRecentRequestCount())
        .recentErrorCount(summary.getRecentErrorCount())
        .recentErrorRate(rate(summary.getRecentErrorCount(), summary.getRecentRequestCount()))
        .previousRequestCount(summary.getPreviousRequestCount())
        .previousErrorCount(summary.getPreviousErrorCount())
        .previousErrorRate(rate(summary.getPreviousErrorCount(), summary.getPreviousRequestCount()))
        .errorCountDeltaPercent(
            deltaPercent(summary.getRecentErrorCount(), summary.getPreviousErrorCount()))
        .recentErrors(statsLogMapper.findRecentErrors(recentFrom, RECENT_ERROR_LIMIT))
        .slowEndpoints(statsLogMapper.findSlowEndpoints(
            recentFrom, now, SLOW_ENDPOINT_MIN_SAMPLES, SLOW_ENDPOINT_LIMIT))
        .schedulers(resolveSchedulers(now))
        .externalApiFailures(statsLogMapper.findExternalApiFailures(recentFrom, EXTERNAL_API_LIMIT))
        .build();
  }

  /**
   * 잡 카탈로그 ⟕ shedlock 을 자바에서 조인한다 — 아직 한 번도 실행되지 않은 잡은
   * shedlock 에 행 자체가 없으므로 SQL LEFT JOIN 으로는 잡을 수 없다.
   */
  private List<SchedulerStatus> resolveSchedulers(Instant now) {
    Map<String, SchedulerLockRow> locks = statsLogMapper.findSchedulerLocks().stream()
        .collect(Collectors.toMap(SchedulerLockRow::getName, Function.identity(), (a, b) -> a));

    return schedulerCatalog.resolveAll().stream()
        .map(job -> toStatus(job, locks.get(job.lockName()), now))
        .toList();
  }

  private SchedulerStatus toStatus(ResolvedJob job, SchedulerLockRow lock, Instant now) {
    Long expectedSeconds = job.expectedInterval() == null ? null : job.expectedInterval().toSeconds();
    Long sinceSeconds = (lock == null || lock.getLockedAt() == null)
        ? null
        : Duration.between(lock.getLockedAt(), now).toSeconds();

    return SchedulerStatus.builder()
        .lockName(job.lockName())
        .displayName(job.displayName())
        .cronExpression(job.cronExpression())
        .lockedAt(lock == null ? null : lock.getLockedAt())
        .lockUntil(lock == null ? null : lock.getLockUntil())
        .lockedBy(lock == null ? null : lock.getLockedBy())
        .expectedIntervalSeconds(expectedSeconds)
        .secondsSinceLockedAt(sinceSeconds)
        .state(resolveState(job, lock, now, expectedSeconds, sinceSeconds))
        .build();
  }

  /**
   * 상태 판정.
   * <p>
   * ⚠️ 여기서 성공/실패를 말하지 않는 것은 의도적이다. {@code AbstractScheduler.proceedScheduler()}
   * 가 예외를 삼키고 로그만 남기며, 실패해도 ShedLock 은 정상 해제되어 {@code locked_at} 이 갱신된다.
   * 즉 shedlock 은 "실행했다"만 증언한다. 지연 여부까지가 이 데이터로 말할 수 있는 전부다.
   */
  private SchedulerHealthState resolveState(ResolvedJob job, SchedulerLockRow lock, Instant now,
      Long expectedSeconds, Long sinceSeconds) {
    if (!job.enabled()) {
      return SchedulerHealthState.DISABLED;
    }
    if (lock == null || lock.getLockedAt() == null) {
      return SchedulerHealthState.NEVER_RUN;
    }
    if (lock.getLockUntil() != null && lock.getLockUntil().isAfter(now)) {
      return SchedulerHealthState.RUNNING;
    }
    // cron 을 못 읽었으면 지연 판정을 하지 않는다 — 멀쩡한 잡을 STALE 로 찍는 것이 더 나쁘다
    if (expectedSeconds == null || sinceSeconds == null) {
      return SchedulerHealthState.OK;
    }
    return sinceSeconds > expectedSeconds * SchedulerCatalog.staleMultiplier()
        ? SchedulerHealthState.STALE
        : SchedulerHealthState.OK;
  }

  private Double rate(long numerator, long denominator) {
    return denominator == 0 ? null : ((double) numerator / denominator) * 100.0;
  }

  private Double deltaPercent(long current, long previous) {
    return previous == 0 ? null : ((double) (current - previous) / previous) * 100.0;
  }
}

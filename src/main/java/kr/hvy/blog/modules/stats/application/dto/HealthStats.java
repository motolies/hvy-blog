package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** GET /api/stats/admin/health — 이상 징후 위젯 전체. 캐시하지 않는다(신선도가 존재 이유). */
@Value
@Builder
@Jacksonized
public class HealthStats {

  Instant windowFrom;
  Instant windowTo;
  int windowHours;

  long recentRequestCount;
  long recentErrorCount;
  Double recentErrorRate;
  long previousRequestCount;
  long previousErrorCount;
  Double previousErrorRate;
  Double errorCountDeltaPercent;

  List<RecentError> recentErrors;
  List<EndpointLatency> slowEndpoints;
  List<SchedulerStatus> schedulers;
  List<ExternalApiStat> externalApiFailures;
}

package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 스케줄러 잡 1개의 마지막 실행 상태. */
@Value
@Builder
@Jacksonized
public class SchedulerStatus {

  String lockName;
  String displayName;
  String cronExpression;
  Instant lockedAt;
  Instant lockUntil;
  String lockedBy;
  Long expectedIntervalSeconds;
  Long secondsSinceLockedAt;
  SchedulerHealthState state;
}

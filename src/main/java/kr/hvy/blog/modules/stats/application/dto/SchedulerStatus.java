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
  /** 이 스케줄러가 부르는 잡을 수동 실행할 때 쓸 모듈·잡 목록. 수동 실행이 없는 잡(핫딜·Jira 등)은 null (2026-09-20, additive) */
  ManualTrigger manualTrigger;
}

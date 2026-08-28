package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** shedlock 테이블 원본 행. 잡 카탈로그와 자바에서 조인한다(아직 실행 안 한 잡은 행이 없다). */
@Value
@Builder
@Jacksonized
public class SchedulerLockRow {

  String name;
  Instant lockedAt;
  Instant lockUntil;
  String lockedBy;
}

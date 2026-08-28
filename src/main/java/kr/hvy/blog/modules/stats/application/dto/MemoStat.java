package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 메모 현황. 메모는 실행/상태 개념이 없어 "마지막 활동 + 최근 건수"로만 표현한다. */
@Value
@Builder
@Jacksonized
public class MemoStat {

  long activeCount;
  long deletedCount;
  long recentCreatedCount;
  Instant lastCreatedAt;
}

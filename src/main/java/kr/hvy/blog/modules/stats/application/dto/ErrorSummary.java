package kr.hvy.blog.modules.stats.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 최근 창 / 직전 창 요청·에러 건수. 비율을 내려면 분모(요청 수)가 필요하다. */
@Value
@Builder
@Jacksonized
public class ErrorSummary {

  long recentRequestCount;
  long recentErrorCount;
  long previousRequestCount;
  long previousErrorCount;
}

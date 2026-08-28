package kr.hvy.blog.modules.stats.application.dto;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 하루치 트래픽 3계열. 조회가 없던 날도 0으로 채워서 반환한다(차트가 구멍을 싫어한다). */
@Value
@Builder
@Jacksonized
public class DailyTraffic {

  LocalDate date;
  long requestCount;
  /** 고유 방문자 근사 — remote_addr DISTINCT. 쿠키가 없으므로 정확한 사람 수는 아니다. */
  long visitorCount;
  long postViewCount;
}

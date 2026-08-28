package kr.hvy.blog.modules.stats.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 경로별 트래픽. uriPattern 은 숫자 세그먼트를 {id} 로 정규화한 값이다. */
@Value
@Builder
@Jacksonized
public class RequestUriStat {

  String uriPattern;
  long requestCount;
  long visitorCount;
  long avgProcessTime;
}

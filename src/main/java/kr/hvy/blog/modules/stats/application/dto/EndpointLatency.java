package kr.hvy.blog.modules.stats.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 엔드포인트별 응답시간. 평균은 꼬리를 숨기므로 p95 를 정본으로 본다. */
@Value
@Builder
@Jacksonized
public class EndpointLatency {

  String uriPattern;
  String httpMethodType;
  long requestCount;
  long avgProcessTime;
  long p95ProcessTime;
  long maxProcessTime;
}

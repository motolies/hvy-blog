package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 외부 API 호출 실패 집계 (tb_api_log — Jira·Claude·Slack·browserless 등 아웃바운드). */
@Value
@Builder
@Jacksonized
public class ExternalApiStat {

  String uriPattern;
  String httpMethodType;
  long callCount;
  long failureCount;
  long avgProcessTime;
  Instant lastCalledAt;
}

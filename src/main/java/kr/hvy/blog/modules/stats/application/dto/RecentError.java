package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import kr.hvy.common.config.jackson.serializer.TsidToStringSerializer;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 최근 에러 1건.
 * <p>
 * stackTraceHead 는 스택트레이스 <b>첫 줄만</b> 자른 값이다 — TEXT 원문을 대시보드로 실어 나르지 않는다.
 * traceId 는 Grafana/Loki 와 /admin/system-log 딥링크의 열쇠다.
 */
@Value
@Builder
@Jacksonized
public class RecentError {

  @JsonSerialize(using = TsidToStringSerializer.class)
  Long id;
  Instant createdAt;
  String traceId;
  String requestUri;
  String controllerName;
  String methodName;
  String httpMethodType;
  String remoteAddr;
  Long processTime;
  String stackTraceHead;
}

package kr.hvy.blog.modules.log.application.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import kr.hvy.common.config.jackson.serializer.TsidToStringSerializer;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@Builder
public class SystemLogSearchResponse {

  @JsonSerialize(using = TsidToStringSerializer.class)
  Long id;
  String traceId;
  String spanId;
  String requestUri;
  String controllerName;
  String methodName;
  String httpMethodType;
  String paramData;
  String responseBody;
  String stackTrace;
  String remoteAddr;
  Long processTime;
  String status;
  String createdAt;
  String createdBy;

}
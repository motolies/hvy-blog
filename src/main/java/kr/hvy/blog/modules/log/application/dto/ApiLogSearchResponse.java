package kr.hvy.blog.modules.log.application.dto;

import java.time.Instant;
import kr.hvy.common.config.jackson.serializer.TsidToStringSerializer;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.annotation.JsonSerialize;

@Value
@Jacksonized
@Builder
public class ApiLogSearchResponse {

  @JsonSerialize(using = TsidToStringSerializer.class)
  Long id;
  String traceId;
  String spanId;
  String requestUri;
  String httpMethodType;
  String requestHeader;
  String requestParam;
  String requestBody;
  String responseStatus;
  String responseBody;
  Long processTime;
  Instant createdAt;
  String createdBy;

}


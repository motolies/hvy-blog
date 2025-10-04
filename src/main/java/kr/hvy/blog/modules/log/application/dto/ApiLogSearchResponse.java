package kr.hvy.blog.modules.log.application.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import kr.hvy.common.config.jackson.serializer.TsidToStringSerializer;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

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
  String createdAt;
  String createdBy;

}


package kr.hvy.blog.modules.log.application.dto;

import java.time.LocalDate;
import kr.hvy.common.application.domain.dto.paging.PageRequest;
import kr.hvy.common.core.code.ApiResponseStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SystemLogSearchRequest extends PageRequest {

  private Long id;
  private String traceId;
  private String spanId;
  private String requestUri;
  private String controllerName;
  private String methodName;
  private String httpMethodType;
  private String paramData;
  private String responseBody;
  private String stackTrace;
  private String remoteAddr;
  private ApiResponseStatus status;
  private LocalDate createdAtFrom;
  private LocalDate createdAtTo;

}

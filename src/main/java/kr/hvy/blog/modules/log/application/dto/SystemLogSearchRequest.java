package kr.hvy.blog.modules.log.application.dto;

import java.time.LocalDateTime;
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
  /**
   * 브라우저 로컬 일시 기준 기록 구간. 종료는 <b>포함</b>이며
   * {@code BrowserDateTimeConverter} 가 클라이언트 존을 적용해 UTC 반개구간으로 바꾼다.
   * 값은 ISO-8601 로컬 일시(예: {@code 2026-09-02T13:45:30}) — 존 표기가 붙으면 안 된다.
   */
  private LocalDateTime createdAtFrom;
  private LocalDateTime createdAtTo;

}

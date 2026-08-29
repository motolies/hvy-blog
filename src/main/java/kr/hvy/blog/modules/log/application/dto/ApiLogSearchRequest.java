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
public class ApiLogSearchRequest extends PageRequest {

  private Long id;
  private String traceId;
  private String spanId;
  private String requestUri;
  private String httpMethodType;
  private String requestHeader;
  private String requestParam;
  private String requestBody;
  private String responseStatus;
  private String responseBody;
  /**
   * 성공/실패 필터. tb_api_log 에는 status 컬럼이 없어 response_status(HTTP 코드)로 파생 판정한다.
   * 값은 시스템 로그와 통일 — Jackson 기본 규칙(enum name)이라 'SUCCESS' / 'FAIL' 만 유효하다.
   */
  private ApiResponseStatus status;
  private LocalDate createdAtFrom;
  private LocalDate createdAtTo;

}

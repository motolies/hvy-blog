package kr.hvy.blog.modules.log.application.dto;

import java.time.LocalDateTime;
import kr.hvy.common.application.domain.dto.paging.PageRequest;
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
  private LocalDateTime createdAtFrom;
  private LocalDateTime createdAtTo;

}

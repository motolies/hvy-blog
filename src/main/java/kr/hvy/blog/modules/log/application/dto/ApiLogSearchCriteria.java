package kr.hvy.blog.modules.log.application.dto;

import java.time.Instant;
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
public class ApiLogSearchCriteria extends PageRequest {

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
  /** 성공 여부 3상태. TRUE=성공만 · FALSE=실패만 · null=전체. 판정식은 ApiLogMapper.xml 이 갖는다. */
  private Boolean responseSuccess;
  private Instant createdAtFrom;
  private Instant createdAtToExclusive;
}

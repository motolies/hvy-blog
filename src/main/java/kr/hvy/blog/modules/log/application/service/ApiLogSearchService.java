package kr.hvy.blog.modules.log.application.service;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.common.core.code.ApiResponseStatus;
import kr.hvy.common.core.time.BrowserDateTimeConverter;
import kr.hvy.common.core.time.UtcDateRange;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchCriteria;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchRequest;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchResponse;
import kr.hvy.blog.modules.log.repository.mapper.ApiLogMapper;
import kr.hvy.common.application.domain.dto.paging.Direction;
import kr.hvy.common.application.domain.dto.paging.OrderBy;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiLogSearchService {

  private final BrowserDateTimeConverter browserDateTimeConverter;
  private final ApiLogMapper apiLogMapper;


  public PageResponse<ApiLogSearchResponse> search(ApiLogSearchRequest request) {
    ApiLogSearchCriteria criteria = toCriteria(request);

    // 기본 정렬 설정: createdAt DESC
    if (CollectionUtils.isEmpty(criteria.getOrderBy())) {
      criteria.getOrderBy().add(
          OrderBy.builder()
              .column("created_at")
              .direction(Direction.DESCENDING)
              .build()
      );
    }

    // MyBatis 조회 (PageInterceptor가 totalCount 자동 설정)
    List<ApiLogSearchResponse> list = apiLogMapper.findBySearchCriteria(criteria);

    // PageResponse 반환
    return PageResponse.<ApiLogSearchResponse>builder()
        .page(criteria.getPage())
        .pageSize(criteria.getPageSize())
        .totalCount(criteria.getTotalCount())
        .list(list)
        .build();
  }

  private ApiLogSearchCriteria toCriteria(ApiLogSearchRequest request) {
    UtcDateRange createdAtRange = browserDateTimeConverter.toUtcDateTimeRange(
        request.getCreatedAtFrom(),
        request.getCreatedAtTo()
    );

    return ApiLogSearchCriteria.builder()
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .orderBy(request.getOrderBy() == null ? new ArrayList<>() : new ArrayList<>(request.getOrderBy()))
        .id(request.getId())
        .traceId(request.getTraceId())
        .spanId(request.getSpanId())
        .requestUri(request.getRequestUri())
        .httpMethodType(request.getHttpMethodType())
        .requestHeader(request.getRequestHeader())
        .requestParam(request.getRequestParam())
        .requestBody(request.getRequestBody())
        .responseStatus(request.getResponseStatus())
        .responseBody(request.getResponseBody())
        .responseSuccess(toResponseSuccess(request.getStatus()))
        .createdAtFrom(createdAtRange.fromInclusive())
        .createdAtToExclusive(createdAtRange.toExclusive())
        .build();
  }

  /**
   * 성공/실패 요청 어휘를 SQL 이 바로 쓸 수 있는 3상태 Boolean 으로 접는다.
   * 같은 메서드가 LocalDate→Instant, createdAtTo→ToExclusive 를 접는 것과 같은 이유 —
   * 매퍼가 enum 을 알 필요가 없고, OGNL 에서 문자열을 비교하는 일도 사라진다.
   */
  private Boolean toResponseSuccess(ApiResponseStatus status) {
    if (status == null) {
      return null;
    }
    return status == ApiResponseStatus.SUCCESS;
  }
}

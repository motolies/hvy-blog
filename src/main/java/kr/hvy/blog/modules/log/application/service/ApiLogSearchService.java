package kr.hvy.blog.modules.log.application.service;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.infra.time.BrowserDateTimeConverter;
import kr.hvy.blog.infra.time.UtcDateRange;
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
    UtcDateRange createdAtRange = browserDateTimeConverter.toUtcDateRange(
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
        .createdAtFromUtc(createdAtRange.fromInclusive())
        .createdAtToUtcExclusive(createdAtRange.toExclusive())
        .build();
  }
}

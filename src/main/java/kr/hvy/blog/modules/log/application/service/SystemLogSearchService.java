package kr.hvy.blog.modules.log.application.service;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.infra.time.BrowserDateTimeConverter;
import kr.hvy.blog.infra.time.UtcDateRange;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchCriteria;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchRequest;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchResponse;
import kr.hvy.blog.modules.log.repository.mapper.SystemLogMapper;
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
public class SystemLogSearchService {

  private final BrowserDateTimeConverter browserDateTimeConverter;
  private final SystemLogMapper systemLogMapper;


  public PageResponse<SystemLogSearchResponse> search(SystemLogSearchRequest request) {
    SystemLogSearchCriteria criteria = toCriteria(request);

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
    List<SystemLogSearchResponse> list = systemLogMapper.findBySearchCriteria(criteria);

    // PageResponse 반환
    return PageResponse.<SystemLogSearchResponse>builder()
        .page(criteria.getPage())
        .pageSize(criteria.getPageSize())
        .totalCount(criteria.getTotalCount())
        .list(list)
        .build();
  }

  private SystemLogSearchCriteria toCriteria(SystemLogSearchRequest request) {
    UtcDateRange createdAtRange = browserDateTimeConverter.toUtcDateRange(
        request.getCreatedAtFrom(),
        request.getCreatedAtTo()
    );

    return SystemLogSearchCriteria.builder()
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .orderBy(request.getOrderBy() == null ? new ArrayList<>() : new ArrayList<>(request.getOrderBy()))
        .id(request.getId())
        .traceId(request.getTraceId())
        .spanId(request.getSpanId())
        .requestUri(request.getRequestUri())
        .controllerName(request.getControllerName())
        .methodName(request.getMethodName())
        .httpMethodType(request.getHttpMethodType())
        .paramData(request.getParamData())
        .responseBody(request.getResponseBody())
        .stackTrace(request.getStackTrace())
        .remoteAddr(request.getRemoteAddr())
        .status(request.getStatus())
        .createdAtFromUtc(createdAtRange.fromInclusive())
        .createdAtToUtcExclusive(createdAtRange.toExclusive())
        .build();
  }
}

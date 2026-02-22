package kr.hvy.blog.modules.log.application.service;

import java.util.List;
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

  private final SystemLogMapper systemLogMapper;


  public PageResponse<SystemLogSearchResponse> search(SystemLogSearchRequest request) {
    // 기본 정렬 설정: createdAt DESC
    if (CollectionUtils.isEmpty(request.getOrderBy())) {
      request.getOrderBy().add(
          OrderBy.builder()
              .column("created_at")
              .direction(Direction.DESCENDING)
              .build()
      );
    }

    // MyBatis 조회 (PageInterceptor가 totalCount 자동 설정)
    List<SystemLogSearchResponse> list = systemLogMapper.findBySearchRequest(request);

    // PageResponse 반환
    return PageResponse.<SystemLogSearchResponse>builder()
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .totalCount(request.getTotalCount())
        .list(list)
        .build();
  }
}

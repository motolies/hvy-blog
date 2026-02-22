package kr.hvy.blog.modules.log.application.service;

import java.util.List;
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

  private final ApiLogMapper apiLogMapper;


  public PageResponse<ApiLogSearchResponse> search(ApiLogSearchRequest request) {
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
    List<ApiLogSearchResponse> list = apiLogMapper.findBySearchRequest(request);

    // PageResponse 반환
    return PageResponse.<ApiLogSearchResponse>builder()
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .totalCount(request.getTotalCount())
        .list(list)
        .build();
  }
}

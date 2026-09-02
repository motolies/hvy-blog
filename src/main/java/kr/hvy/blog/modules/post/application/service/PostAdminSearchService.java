package kr.hvy.blog.modules.post.application.service;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchCriteria;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchRequest;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchResponse;
import kr.hvy.blog.modules.post.repository.mapper.PostMapper;
import kr.hvy.common.application.domain.dto.paging.Direction;
import kr.hvy.common.application.domain.dto.paging.OrderBy;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import kr.hvy.common.core.time.BrowserDateTimeConverter;
import kr.hvy.common.core.time.UtcDateRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/**
 * 관리자 글 목록 검색. SystemLogSearchService 와 동일한 Request→Criteria→PageResponse 형태를 따른다.
 * <p>
 * 반환 타입이 {@code kr.hvy.common...PageResponse} 인 것이 중요하다 — 프론트의 useServerGrid 는
 * {@code response.list} / {@code response.totalCount} 를 읽는다. 다른 페이지 형태를 반환하면
 * 에러도 로그도 없이 <b>빈 그리드</b>가 뜬다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostAdminSearchService {

  private final PostMapper postMapper;
  private final BrowserDateTimeConverter browserDateTimeConverter;

  public PageResponse<PostAdminSearchResponse> search(PostAdminSearchRequest request) {
    PostAdminSearchCriteria criteria = toCriteria(request);

    // 기본 정렬: 최근 수정 순 — 글 목록의 일차 용도가 "방금 건드린 글 찾기"다
    // 조인된 tb_post_draft 에도 updated_at 이 있어 테이블 별칭이 필수다(HotDealItemSearchService 와 동일 패턴).
    // 프론트가 보내는 camelCase 정렬 키는 SELECT 별칭으로 해석되므로 별도 매핑은 필요 없다.
    if (CollectionUtils.isEmpty(criteria.getOrderBy())) {
      criteria.getOrderBy().add(
          OrderBy.builder().column("p.updated_at").direction(Direction.DESCENDING).build());
    }

    // PageInterceptor 가 totalCount 를 채운다
    List<PostAdminSearchResponse> list = postMapper.findByAdminSearchCriteria(criteria);

    return PageResponse.<PostAdminSearchResponse>builder()
        .page(criteria.getPage())
        .pageSize(criteria.getPageSize())
        .totalCount(criteria.getTotalCount())
        .list(list)
        .build();
  }

  private PostAdminSearchCriteria toCriteria(PostAdminSearchRequest request) {
    UtcDateRange dateRange = browserDateTimeConverter.toUtcDateTimeRange(
        request.getDateFrom(), request.getDateTo());

    return PostAdminSearchCriteria.builder()
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .orderBy(request.getOrderBy() == null
            ? new ArrayList<>() : new ArrayList<>(request.getOrderBy()))
        .subject(request.getSubject())
        .categoryId(request.getCategoryId())
        .tagName(request.getTagName())
        .status(request.getStatus())
        .publicAccess(request.getPublicAccess())
        .hasDraft(request.getHasDraft())
        .minViewCount(request.getMinViewCount())
        .maxViewCount(request.getMaxViewCount())
        .dateField(request.getDateField() == null
            ? PostAdminSearchRequest.DATE_FIELD_CREATED_AT : request.getDateField())
        .dateFrom(dateRange.fromInclusive())
        .dateToExclusive(dateRange.toExclusive())
        .build();
  }
}

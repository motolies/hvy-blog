package kr.hvy.blog.modules.hotdeal.application.service;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.common.core.time.BrowserDateTimeConverter;
import kr.hvy.common.core.time.UtcDateRange;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchCriteria;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchRequest;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchResponse;
import kr.hvy.blog.modules.hotdeal.repository.mapper.HotDealItemMapper;
import kr.hvy.common.application.domain.dto.paging.Direction;
import kr.hvy.common.application.domain.dto.paging.OrderBy;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotDealItemSearchService {

  private final BrowserDateTimeConverter browserDateTimeConverter;
  private final HotDealItemMapper hotDealItemMapper;

  public PageResponse<HotDealItemSearchResponse> search(HotDealItemSearchRequest request) {
    HotDealItemSearchCriteria criteria = toCriteria(request);

    if (CollectionUtils.isEmpty(criteria.getOrderBy())) {
      criteria.getOrderBy().add(
          OrderBy.builder()
              .column("i.scraped_at")
              .direction(Direction.DESCENDING)
              .build()
      );
    }

    List<HotDealItemSearchResponse> list = hotDealItemMapper.findBySearchCriteria(criteria);

    return PageResponse.<HotDealItemSearchResponse>builder()
        .page(criteria.getPage())
        .pageSize(criteria.getPageSize())
        .totalCount(criteria.getTotalCount())
        .list(list)
        .build();
  }

  private HotDealItemSearchCriteria toCriteria(HotDealItemSearchRequest request) {
    UtcDateRange scrapedRange = browserDateTimeConverter.toUtcDateRange(
        request.getScrapedAtFrom(),
        request.getScrapedAtTo()
    );

    return HotDealItemSearchCriteria.builder()
        .page(request.getPage())
        .pageSize(request.getPageSize())
        .orderBy(request.getOrderBy() == null ? new ArrayList<>() : new ArrayList<>(request.getOrderBy()))
        .siteId(request.getSiteId())
        .title(request.getTitle())
        .notified(request.getNotified())
        .dealCategory(request.getDealCategory())
        .scrapedAtFrom(scrapedRange.fromInclusive())
        .scrapedAtToExclusive(scrapedRange.toExclusive())
        .minRecommendationCount(request.getMinRecommendationCount())
        .maxRecommendationCount(request.getMaxRecommendationCount())
        .minViewCount(request.getMinViewCount())
        .maxViewCount(request.getMaxViewCount())
        .minCommentCount(request.getMinCommentCount())
        .maxCommentCount(request.getMaxCommentCount())
        .build();
  }
}

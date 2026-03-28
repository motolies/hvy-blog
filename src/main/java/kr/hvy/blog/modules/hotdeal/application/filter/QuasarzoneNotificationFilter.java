package kr.hvy.blog.modules.hotdeal.application.filter;

import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.application.specification.MinCommentCountSpecification;
import kr.hvy.blog.modules.hotdeal.application.specification.MinViewCountSpecification;
import kr.hvy.blog.modules.hotdeal.application.specification.RecommendationRatioSpecification;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import kr.hvy.common.core.specification.Specification;
import org.springframework.stereotype.Component;

/**
 * 퀘이사존 알림 필터.
 *
 * <p>아래 조건 중 하나라도 충족 시 알림 대상 (OR 조합):
 * <ul>
 *   <li>조회수 >= minViewCount (DB 설정, 기본 3000)</li>
 *   <li>추천수 >= minRecommendation AND 비추천수 + 0 < 추천수 (퀘이사존은 비추천 없음)</li>
 *   <li>댓글수 >= minCommentCount (DB 설정, 기본 15)</li>
 * </ul>
 */
@Component
public class QuasarzoneNotificationFilter implements DealNotificationFilter {

  private static final int RECOMMENDATION_MARGIN = 0;

  @Override
  public DealSiteCode getSiteCode() {
    return DealSiteCode.QUASARZONE;
  }

  @Override
  public Specification<ScrapedDeal> createSpecification(HotDealSite site) {
    return new MinViewCountSpecification(site.getMinViewCount())
        .or(new RecommendationRatioSpecification(site.getMinRecommendation(), RECOMMENDATION_MARGIN))
        .or(new MinCommentCountSpecification(site.getMinCommentCount()));
  }
}

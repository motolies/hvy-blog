package kr.hvy.blog.modules.hotdeal.application.specification;

import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.common.core.specification.Specification;
import lombok.RequiredArgsConstructor;

/**
 * 추천/비추천 비율 기반 필터.
 *
 * <p>추천수가 최소값 이상이고, 비추천수 + margin < 추천수 일 때 통과.
 *
 * <p>예시 (minRecommendation=5, margin=2):
 * <ul>
 *   <li>추천 10, 비추천 3 → 통과 (10 >= 5 AND 3+2 < 10)</li>
 *   <li>추천 6, 비추천 5 → 탈락 (6 >= 5 BUT 5+2 = 7 >= 6)</li>
 *   <li>추천 3, 비추천 0 → 탈락 (3 < 5)</li>
 * </ul>
 */
@RequiredArgsConstructor
public class RecommendationRatioSpecification implements Specification<ScrapedDeal> {

  private final int minRecommendation;
  private final int margin;

  @Override
  public boolean isSatisfiedBy(ScrapedDeal deal) {
    return deal.getRecommendationCount() >= minRecommendation
        && deal.getUnrecommendationCount() + margin < deal.getRecommendationCount();
  }

  @Override
  public String getErrorMessage() {
    return String.format("추천 비율 조건 미충족 (최소추천: %d, 마진: %d)", minRecommendation, margin);
  }
}

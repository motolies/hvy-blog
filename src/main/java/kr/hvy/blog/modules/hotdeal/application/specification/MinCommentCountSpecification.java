package kr.hvy.blog.modules.hotdeal.application.specification;

import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.common.core.specification.Specification;
import lombok.RequiredArgsConstructor;

/**
 * 최소 댓글수 조건.
 */
@RequiredArgsConstructor
public class MinCommentCountSpecification implements Specification<ScrapedDeal> {

  private final int minCommentCount;

  @Override
  public boolean isSatisfiedBy(ScrapedDeal deal) {
    return deal.getCommentCount() >= minCommentCount;
  }

  @Override
  public String getErrorMessage() {
    return String.format("댓글수 조건 미충족 (최소: %d)", minCommentCount);
  }
}

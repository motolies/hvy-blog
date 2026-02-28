package kr.hvy.blog.modules.hotdeal.application.specification;

import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.common.core.specification.Specification;
import lombok.RequiredArgsConstructor;

/**
 * 최소 조회수 조건.
 */
@RequiredArgsConstructor
public class MinViewCountSpecification implements Specification<ScrapedDeal> {

  private final int minViewCount;

  @Override
  public boolean isSatisfiedBy(ScrapedDeal deal) {
    return deal.getViewCount() >= minViewCount;
  }

  @Override
  public String getErrorMessage() {
    return String.format("조회수 조건 미충족 (최소: %d)", minViewCount);
  }
}

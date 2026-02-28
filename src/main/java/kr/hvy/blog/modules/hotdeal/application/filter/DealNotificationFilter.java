package kr.hvy.blog.modules.hotdeal.application.filter;

import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import kr.hvy.common.core.specification.Specification;

/**
 * 사이트별 핫딜 알림 필터 전략 인터페이스.
 *
 * <p>새로운 사이트 추가 시 이 인터페이스를 구현하는 @Component 클래스만 생성하면 된다.
 * HotDealSite의 DB 설정값을 threshold로 활용하여 Specification을 조합한다.
 */
public interface DealNotificationFilter {

  DealSiteCode getSiteCode();

  /**
   * 해당 사이트의 알림 조건 Specification 생성.
   *
   * @param site DB에서 조회한 사이트 설정 (threshold 값 참조)
   * @return 알림 대상 여부를 판단하는 Specification
   */
  Specification<ScrapedDeal> createSpecification(HotDealSite site);
}

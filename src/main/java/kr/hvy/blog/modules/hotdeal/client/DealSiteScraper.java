package kr.hvy.blog.modules.hotdeal.client;

import java.util.List;
import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;

/**
 * 핫딜 사이트 스크래퍼 전략 인터페이스.
 *
 * 새로운 사이트 추가 시 이 인터페이스를 구현하는 @Component 클래스만 생성하면 된다.
 * 기존 코드를 수정할 필요가 없다 (개방-폐쇄 원칙).
 */
public interface DealSiteScraper {

  /**
   * 이 스크래퍼가 담당하는 사이트 코드 반환.
   * HotDealSite.siteCode 와 매핑됨.
   */
  DealSiteCode getSiteCode();

  /**
   * 사이트의 핫딜 게시글을 스크래핑하여 반환.
   *
   * @param siteConfig DB에서 조회한 사이트 설정
   * @return 스크래핑된 딜 목록 (필터링 전 원시 데이터)
   */
  List<ScrapedDeal> scrape(HotDealSite siteConfig);
}

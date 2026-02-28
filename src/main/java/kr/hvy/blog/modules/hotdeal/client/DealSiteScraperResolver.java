package kr.hvy.blog.modules.hotdeal.client;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

/**
 * 사이트 코드로 적합한 스크래퍼를 찾아주는 Resolver.
 *
 * Spring이 DealSiteScraper 구현체들을 자동으로 주입하므로,
 * 새 스크래퍼를 @Component로 등록하기만 하면 자동으로 활성화된다.
 */
@Slf4j
@Component
public class DealSiteScraperResolver {

  private final Map<DealSiteCode, DealSiteScraper> scraperMap;

  public DealSiteScraperResolver(List<DealSiteScraper> scrapers) {
    this.scraperMap = scrapers.stream()
        .collect(Collectors.toMap(DealSiteScraper::getSiteCode, Function.identity()));
    log.info("핫딜 스크래퍼 등록 완료: {}", scraperMap.keySet());
  }

  /**
   * 사이트 코드에 해당하는 스크래퍼를 반환.
   *
   * @return 스크래퍼가 없으면 null 반환 (스크래퍼 미구현 사이트는 graceful하게 건너뜀)
   */
  public DealSiteScraper resolve(DealSiteCode siteCode) {
    DealSiteScraper scraper = scraperMap.get(siteCode);
    if (ObjectUtils.isEmpty(scraper)) {
      log.warn("스크래퍼 미등록 사이트: siteCode={}", siteCode);
      return null;
    }
    return scraper;
  }
}

package kr.hvy.blog.modules.hotdeal.application.filter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

/**
 * 사이트 코드로 적합한 알림 필터를 찾아주는 Resolver.
 *
 * <p>Spring이 DealNotificationFilter 구현체들을 자동으로 주입하므로,
 * 새 필터를 @Component로 등록하기만 하면 자동으로 활성화된다 (개방-폐쇄 원칙).
 */
@Slf4j
@Component
public class DealNotificationFilterResolver {

  private final Map<DealSiteCode, DealNotificationFilter> filterMap;

  public DealNotificationFilterResolver(List<DealNotificationFilter> filters) {
    this.filterMap = filters.stream()
        .collect(Collectors.toMap(DealNotificationFilter::getSiteCode, Function.identity()));
    log.info("핫딜 알림 필터 등록 완료: {}", filterMap.keySet());
  }

  /**
   * 사이트 코드에 해당하는 알림 필터를 반환.
   *
   * @return 필터가 없으면 null 반환 (기본 fallback 로직 사용)
   */
  public DealNotificationFilter resolve(DealSiteCode siteCode) {
    DealNotificationFilter filter = filterMap.get(siteCode);
    if (ObjectUtils.isEmpty(filter)) {
      log.debug("알림 필터 미등록 사이트: siteCode={}", siteCode);
      return null;
    }
    return filter;
  }
}

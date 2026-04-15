package kr.hvy.blog.modules.common.cache.domain.code;

import java.time.Duration;
import kr.hvy.common.config.cache.TwoTierCacheProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CacheType {

  SEARCH_ENGINE(TwoTierCacheProperties.twoTier(CacheConstant.SEARCH_ENGINE, Duration.ofHours(1), 200, Duration.ofDays(1))),
  CATEGORY(TwoTierCacheProperties.twoTier(CacheConstant.CATEGORY, Duration.ofHours(1), 200, Duration.ofDays(1)));

  private final TwoTierCacheProperties properties;
}

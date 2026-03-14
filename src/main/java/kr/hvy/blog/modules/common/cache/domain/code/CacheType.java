package kr.hvy.blog.modules.common.cache.domain.code;

import java.time.Duration;
import kr.hvy.common.config.cache.TwoTierCacheProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CacheType {

  SEARCH_ENGINE(TwoTierCacheProperties.twoTier(CacheConstant.SEARCH_ENGINE, Duration.ofHours(1), 200, Duration.ofDays(1))),
  CATEGORY(TwoTierCacheProperties.twoTier(CacheConstant.CATEGORY, Duration.ofHours(1), 200, Duration.ofDays(1))),

  // MasterCode L1+L2 캐시
  MASTER_CODE_TREE(TwoTierCacheProperties.twoTier(CacheConstant.MASTER_CODE_TREE, Duration.ofHours(6), 50, Duration.ofDays(1))),
  MASTER_CODE_NODE(TwoTierCacheProperties.twoTier(CacheConstant.MASTER_CODE_NODE, Duration.ofHours(6), 200, Duration.ofDays(1))),
  MASTER_CODE_CHILDREN(TwoTierCacheProperties.twoTier(CacheConstant.MASTER_CODE_CHILDREN, Duration.ofHours(6), 100, Duration.ofDays(1)));

  private final TwoTierCacheProperties properties;
}

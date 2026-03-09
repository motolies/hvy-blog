package kr.hvy.blog.modules.common.cache.domain.code;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CacheType {

  SEARCH_ENGINE(CacheConstant.SEARCH_ENGINE, Duration.ofMinutes(10), 200, false),
  CATEGORY(CacheConstant.CATEGORY, Duration.ofDays(1), 200, false),

  // MasterCode L1 캐시 타입들 (Caffeine, 짧은 TTL)
  MASTER_CODE_TREE(CacheConstant.MASTER_CODE_TREE, Duration.ofMinutes(10), 50, false),
  MASTER_CODE_NODE(CacheConstant.MASTER_CODE_NODE, Duration.ofMinutes(10), 200, false),
  MASTER_CODE_CHILDREN(CacheConstant.MASTER_CODE_CHILDREN, Duration.ofMinutes(10), 100, false);

  private final String name;
  private final Duration timeout;
  private final int maxSize;
  private final boolean allowNullValues;
}

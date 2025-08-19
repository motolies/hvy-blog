package kr.hvy.blog.modules.common.cache.domain.code;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CacheType {

  SEARCH_ENGINE(CacheConstant.SEARCH_ENGINE, Duration.ofMinutes(10), 200, false),
  CATEGORY(CacheConstant.CATEGORY, Duration.ofDays(1), 200, false),

  // CommonCode 캐시 타입들
  COMMON_CODE_CLASS(CacheConstant.COMMON_CODE_CLASS, Duration.ofHours(6), 100, false),
  COMMON_CODE_DATA(CacheConstant.COMMON_CODE_DATA, Duration.ofHours(2), 500, false),
  COMMON_CODE_TREE(CacheConstant.COMMON_CODE_TREE, Duration.ofHours(1), 200, false);

  private final String name;
  private final Duration timeout;
  private final int maxSize;
  private final boolean allowNullValues;


}

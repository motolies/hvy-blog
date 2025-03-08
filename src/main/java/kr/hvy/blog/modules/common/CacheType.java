package kr.hvy.blog.modules.common;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CacheType {

  SEARCH_ENGINE(CacheConstant.SEARCH_ENGINE, Duration.ofMinutes(10), 200, false),
  CATEGORY(CacheConstant.CATEGORY, Duration.ofDays(1), 200, false);

  private final String name;
  private final Duration timeout;
  private final int maxSize;
  private final boolean allowNullValues;


}

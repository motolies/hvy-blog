package kr.hvy.blog.infra.config;

import java.util.Arrays;
import kr.hvy.blog.modules.common.cache.domain.code.CacheType;
import kr.hvy.common.config.cache.TwoTierCacheConfigurer;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig extends TwoTierCacheConfigurer {

  @Bean
  public CacheManager cacheManager() {
    return super.twoTierCacheManager(
        Arrays.stream(CacheType.values())
            .map(CacheType::getProperties)
            .toList()
    );
  }
}

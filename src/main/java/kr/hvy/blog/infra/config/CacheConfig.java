package kr.hvy.blog.infra.config;

import java.util.Arrays;
import kr.hvy.blog.modules.common.cache.domain.code.CacheType;
import kr.hvy.common.config.cache.CacheConfigurer;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig extends CacheConfigurer {

  @Bean
  public CacheManager cacheManager() {
    return super.localCacheManager(
        Arrays.stream(CacheType.values())
            .map(cache -> super.localCache(cache.getName(), cache.getTimeout(), cache.getMaxSize(), cache.isAllowNullValues()))
            .toList()
    );
  }

}

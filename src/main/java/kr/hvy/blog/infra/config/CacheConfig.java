package kr.hvy.blog.infra.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kr.hvy.blog.modules.common.cache.domain.code.CacheType;
import kr.hvy.common.config.cache.TwoTierCacheConfigurer;
import kr.hvy.common.config.cache.TwoTierCacheProperties;
import kr.hvy.common.infrastructure.redis.impl.masterdata.cache.MasterCodeCacheProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig extends TwoTierCacheConfigurer {

  @Bean
  public CacheManager cacheManager() {
    List<TwoTierCacheProperties> props = new ArrayList<>();
    Arrays.stream(CacheType.values()).map(CacheType::getProperties).forEach(props::add);
    props.addAll(MasterCodeCacheProperties.all());
    return super.twoTierCacheManager(props);
  }
}

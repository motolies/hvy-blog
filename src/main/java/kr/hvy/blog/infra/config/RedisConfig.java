package kr.hvy.blog.infra.config;

import kr.hvy.common.config.RedisConfigurer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class RedisConfig extends RedisConfigurer {

  public RedisConfig(RedisProperties redisProperties) {
    super(redisProperties);
  }


}

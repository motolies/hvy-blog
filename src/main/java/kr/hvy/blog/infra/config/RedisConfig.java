package kr.hvy.blog.infra.config;

import kr.hvy.common.infrastructure.redis.config.RedisConfigurer;
import kr.hvy.common.infrastructure.redis.util.RedissonUtils;
import org.redisson.api.RedissonClient;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class RedisConfig extends RedisConfigurer {

  public RedisConfig(DataRedisProperties redisProperties) {
    super(redisProperties);
  }

  @Bean
  public RedissonUtils redissonUtils(RedissonClient redissonClient) {
    return new RedissonUtils(redissonClient);
  }
}

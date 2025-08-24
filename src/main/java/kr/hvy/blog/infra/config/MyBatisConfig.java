package kr.hvy.blog.infra.config;

import kr.hvy.common.infrastructure.database.mybatis.interceptor.PageInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyBatisConfig {

  @Bean
  public PageInterceptor gridPagingInterceptor() {
    return new PageInterceptor();
  }
}

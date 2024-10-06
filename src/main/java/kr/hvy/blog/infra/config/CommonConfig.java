package kr.hvy.blog.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hvy.common.mapper.ObjectMapperConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class CommonConfig {

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    return ObjectMapperConfigurer.getObjectMapper();
  }

}

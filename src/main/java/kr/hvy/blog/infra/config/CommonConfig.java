package kr.hvy.blog.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.client.Interceptor.ApiLogInterceptor;
import kr.hvy.common.config.ObjectMapperConfigurer;
import kr.hvy.common.notify.Notify;
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

  @Bean
  public ApiLogInterceptor apiLogInterceptor(Optional<Notify> notify){
    return new ApiLogInterceptor(notify, SlackChannel.ERROR.getChannel());
  }

}

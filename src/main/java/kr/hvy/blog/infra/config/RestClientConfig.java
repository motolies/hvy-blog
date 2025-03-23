package kr.hvy.blog.infra.config;

import kr.hvy.common.config.RestClientConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientConfig extends RestClientConfigurer {

  @Bean
  public RestClient RestClient() {
    return restClient(10,10);
  }

}

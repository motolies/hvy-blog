package kr.hvy.blog.infra.config;

import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.client.config.RestClientConfigurer;
import kr.hvy.common.infrastructure.client.rest.RestApi;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientConfig extends RestClientConfigurer {

  @Bean
  public RestClient RestClient() {
    return restClient(10, 10);
  }

  @Bean
  public RestApi RestApi(RestClient restClient, Optional<Notify> notify) {
    return new RestApi(restClient, notify, Optional.of(SlackChannel.ERROR.getChannel()));
  }


}

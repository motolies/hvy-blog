package kr.hvy.blog.infra.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.jira.infrastructure.config.JiraProperties;
import kr.hvy.common.infrastructure.client.config.RestClientConfigurer;
import kr.hvy.common.infrastructure.client.rest.Interceptor.ApiLogInterceptor;
import kr.hvy.common.infrastructure.client.rest.RestApi;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class RestClientConfig extends RestClientConfigurer {

  private final ApiLogInterceptor apiLogInterceptor;


  @Bean("restClient")
  public RestClient RestClient() {
    return restClient(10, 10);
  }

  @Bean("restApi")
  public RestApi RestApi(@Qualifier("restClient") RestClient restClient, Optional<Notify> notify) {
    return new RestApi(restClient, notify, Optional.of(SlackChannel.ERROR.getChannel()));
  }

  @Bean("jiraRestClient")
  public RestClient jiraRestClient(JiraProperties jiraProperties) {
    // Basic Authentication 헤더 생성
    String authString = jiraProperties.getUsername() + ":" + jiraProperties.getApiToken();
    String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

    return RestClient.builder()
        .baseUrl(jiraProperties.getUrl())
        .requestInterceptor(apiLogInterceptor)
        .defaultHeader("Authorization", "Basic " + encodedAuth)
        .defaultHeader("Accept", "application/json")
        .defaultHeader("Content-Type", "application/json")
        .build();
  }

  @Bean("jiraRestApi")
  public RestApi jiraRestApi(@Qualifier("jiraRestClient") RestClient jiraRestClient, Optional<Notify> notify) {
    return new RestApi(jiraRestClient, notify, Optional.of(SlackChannel.ERROR.getChannel()));
  }

}

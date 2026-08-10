package kr.hvy.blog.infra.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.hotdeal.client.BrowserProperties;
import kr.hvy.blog.modules.jira.client.JiraProperties;
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

  /**
   * api_log 에 저장할 browserless 응답 본문 상한. 렌더링된 HTML 이 수백 KB 라 상한을 둔다
   */
  private static final int BROWSERLESS_MAX_LOG_BODY_BYTES = 1024 * 1024;

  private final ApiLogInterceptor apiLogInterceptor;


  @Bean("restClient")
  public RestClient RestClient() {
    return restClient(10, 10);
  }

  @Bean("restApi")
  public RestApi RestApi(@Qualifier("restClient") RestClient restClient, Optional<Notify> notify) {
    return new RestApi(restClient, notify, Optional.of(SlackChannel.ERROR.getChannel()));
  }

  @Bean("claudeRestClient")
  public RestClient claudeRestClient() {
    return restClient(10, 60, "https://api.anthropic.com");
  }

  /**
   * browserless(Chromium 사이드카) 전용 RestClient.
   *
   * 응답이 렌더링된 HTML 이라 api_log 본문 상한을 지정한다.
   * baseUrl 을 두지 않는다 - 호출부가 조립해 둔 절대 URI 를 그대로 써야 launch 파라미터의 퍼센트 인코딩이 보존된다.
   */
  @Bean("browserlessRestClient")
  public RestClient browserlessRestClient(BrowserProperties browserProperties) {
    return restClient((int) browserProperties.getConnectTimeout().toSeconds(),
        (int) browserProperties.getRequestTimeout().toSeconds(),
        null,
        BROWSERLESS_MAX_LOG_BODY_BYTES);
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

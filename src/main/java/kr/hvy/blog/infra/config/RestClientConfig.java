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
import kr.hvy.blog.modules.stock.client.KisProperties;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

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

  /**
   * 한국투자증권(KIS) Open API 전용 RestClient.
   * <p>
   * {@link RestClientConfigurer#restClient} 헬퍼는 ApiLogInterceptor 를 항상 부착하므로 쓰지 않는다 —
   * KIS 는 일일 증분만 1.6만 호출, 백필은 12만 호출이라 tb_api_log 가 수백만 행으로 폭증한다.
   * 대신 KisApiClient 가 실패 호출만 tb_kis_api_failure 에 기록하고, 성공 호출은
   * tb_stock_collect_run.api_call_count 카운터로만 집계한다.
   * 15req/s 를 수 시간 유지하려면 keep-alive 커넥션 풀이 필수라 HttpClient5 풀링 팩토리를 직접 구성한다.
   * RestApi 파사드도 쓰지 않는다(non-2xx 마다 Slack 을 쏘므로 EGW00201 한 번에 수백 개 알림이 간다).
   */
  @Bean("kisRestClient")
  public RestClient kisRestClient(KisProperties kisProperties) {
    KisProperties.Http http = kisProperties.getHttp();

    ConnectionConfig connectionConfig = ConnectionConfig.custom()
        .setConnectTimeout(Timeout.of(http.getConnectTimeout()))
        .build();
    PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
        .setDefaultConnectionConfig(connectionConfig)
        .setMaxConnTotal(http.getMaxConnections())
        .setMaxConnPerRoute(http.getMaxConnections())
        .build();
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.of(http.getConnectTimeout()))
        .setResponseTimeout(Timeout.of(http.getResponseTimeout()))
        .build();
    // 자동 재시도는 KisApiClient 가 EGW00201/5xx 분류에 따라 직접 수행한다
    CloseableHttpClient httpClient = HttpClients.custom()
        .setConnectionManager(connectionManager)
        .setDefaultRequestConfig(requestConfig)
        .disableAutomaticRetries()
        .build();

    return RestClient.builder()
        .baseUrl(kisProperties.getBaseUrl())
        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
        .defaultHeader("content-type", "application/json; charset=utf-8")
        .build();
  }

}

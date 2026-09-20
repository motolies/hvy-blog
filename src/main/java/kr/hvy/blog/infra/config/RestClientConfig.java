package kr.hvy.blog.infra.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiBearerAuthInterceptor;
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
import kr.hvy.blog.modules.stock.client.GdeltProperties;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.MacroProperties;
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

  private static final String OPENAI_BASE_URL = "https://api.openai.com";
  /** 연결 수립 타임아웃(초). 응답 타임아웃은 advisor.model.timeout-seconds(추론 모델은 수십 초) */
  private static final int OPENAI_CONNECT_TIMEOUT_SECONDS = 10;
  /**
   * api_log 에 저장할 OpenAI 요청/응답 본문 상한. 도구 루프는 라운드마다 전체 입력(시스템 프롬프트·스레드 히스토리·도구 결과)을 재전송하므로 상한을 둔다
   */
  private static final int OPENAI_MAX_LOG_BODY_BYTES = 1024 * 1024;
  private static final int OPENAI_MAX_TOTAL_CONNECTIONS = 100;
  private static final int OPENAI_MAX_CONNECTIONS_PER_ROUTE = 20;
  /** 거시 CSV 응답의 api_log 본문 상한 (CBOE 전체 이력 파일이 500KB 라 머리만 남긴다) */
  private static final int MACRO_MAX_LOG_BODY_BYTES = 8 * 1024;
  /** GDELT JSON 응답의 api_log 본문 상한 */
  private static final int GDELT_MAX_LOG_BODY_BYTES = 64 * 1024;

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
   * OpenAI Responses API(advisor judge/assist/채팅 봇 전부) 전용 RestClient. 호출 1건이 그대로 tb_api_log 1행이 된다(전송 재시도 off·본문 상한 1 MiB).
   * 공식 SDK 는 쓰지 않으므로 advisor 의 모든 OpenAI 호출이 이 클라이언트를 지난다(2026-09-19).
   * <p>
   * 인증 인터셉터는 <b>커스텀 인터셉터 목록</b>으로 넘긴다 — 헬퍼가 api_log 인터셉터를 먼저, 커스텀을 뒤에 붙이므로 Bearer 키는 api_log 인터셉터가
   * 저장하는 원본 헤더에 들어가지 않는다({@link OpenAiBearerAuthInterceptor} 참고). defaultHeader 로 넣으면 키가 request_header 에 남는다.
   * 키는 호출 시점에 {@link AdvisorProperties#openAiApiKey()} 로 읽는다.
   */
  @Bean("openAiRestClient")
  public RestClient openAiRestClient(AdvisorProperties advisorProperties) {
    return restClient(OPENAI_MAX_TOTAL_CONNECTIONS, OPENAI_MAX_CONNECTIONS_PER_ROUTE, OPENAI_CONNECT_TIMEOUT_SECONDS,
        advisorProperties.getModel().getTimeoutSeconds(), OPENAI_BASE_URL,
        List.of(new OpenAiBearerAuthInterceptor(advisorProperties::openAiApiKey)), OPENAI_MAX_LOG_BODY_BYTES);
  }

  /**
   * 거시 지표 공개 CSV(CBOE·재무부) 전용 RestClient. baseUrl 없음(시리즈마다 절대 URL). CBOE 전체 이력 CSV 가 500KB 안팎이라 api_log 본문은 8KB 로 자른다
   * (호출 1건 = 1행은 유지 — 원천 형식 변경을 사후에 볼 수 있게).
   */
  @Bean("macroRestClient")
  public RestClient macroRestClient(MacroProperties macroProperties) {
    return restClient(macroProperties.getConnectTimeoutSeconds(), macroProperties.getTimeoutSeconds(), null, MACRO_MAX_LOG_BODY_BYTES);
  }

  /**
   * GDELT DOC 2.0 API 전용 RestClient. 응답 JSON 은 수 KB~수십 KB 라 api_log 본문 상한 64KB. 스로틀·429 재시도는 GdeltDocAdapter 가 한다.
   */
  @Bean("gdeltRestClient")
  public RestClient gdeltRestClient(GdeltProperties gdeltProperties) {
    return restClient(gdeltProperties.getConnectTimeoutSeconds(), gdeltProperties.getTimeoutSeconds(), null, GDELT_MAX_LOG_BODY_BYTES);
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

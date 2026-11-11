package kr.hvy.blog.modules.advisor.client.llm;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/**
 * LLM 공급자 API 키 인증 인터셉터 — 원본 요청 헤더는 건드리지 않고 <b>헤더 사본</b>에만 키를 넣어 다음 단계로 넘긴다.
 * OpenAI(Authorization: Bearer)와 Anthropic(x-api-key)이 헤더 이름·값 형식만 다르고 같은 일을 해서 하나로 합쳤다(2026-11-11).
 * <p>
 * {@code RestClientConfigurer} 는 api_log 인터셉터를 먼저, 커스텀 인터셉터를 뒤에 붙인다. api_log 인터셉터는 자기가 받은 원본 요청의 헤더를
 * 원문 그대로 저장하므로, 이 인터셉터가 그 뒤에서 사본에만 키를 넣으면 {@code tb_api_log.request_header} 에 API 키가 남지 않는다.
 * 순서가 바뀌면(이 인터셉터가 앞) 키가 그대로 저장된다 — {@code RestClientConfig} 의 빈 조립 순서에 의존하는 계약.
 * <p>
 * 키는 호출 시점에 읽는다(기동 시 캡처 금지) — 키가 비어 있는 채로 기동해도 되는 advisor 계약(호출 시점 401)을 지키기 위해서다.
 */
public class ApiKeyHeaderInterceptor implements ClientHttpRequestInterceptor {

  private static final String BEARER_PREFIX = "Bearer ";

  private final String headerName;
  private final UnaryOperator<String> headerValue;
  private final Supplier<String> apiKey;

  /**
   * @param headerName  키를 싣는 헤더 이름
   * @param headerValue 키 → 헤더 값(접두사를 붙이는 등)
   * @param apiKey      키 공급자. 호출마다 읽는다
   */
  private ApiKeyHeaderInterceptor(String headerName, UnaryOperator<String> headerValue, Supplier<String> apiKey) {
    this.headerName = Objects.requireNonNull(headerName, "headerName");
    this.headerValue = Objects.requireNonNull(headerValue, "headerValue");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
  }

  /**
   * {@code Authorization: Bearer <키>} 로 인증하는 인터셉터(OpenAI).
   */
  public static ApiKeyHeaderInterceptor bearer(Supplier<String> apiKey) {
    return new ApiKeyHeaderInterceptor(HttpHeaders.AUTHORIZATION, key -> BEARER_PREFIX + key, apiKey);
  }

  /**
   * 지정한 헤더에 키를 그대로 싣는 인터셉터(Anthropic 의 {@code x-api-key}).
   */
  public static ApiKeyHeaderInterceptor header(String headerName, Supplier<String> apiKey) {
    return new ApiKeyHeaderInterceptor(headerName, UnaryOperator.identity(), apiKey);
  }

  /**
   * 키가 있으면 헤더 사본에 넣어 넘기고, 비어 있으면 요청을 그대로 넘긴다(서버가 401 로 답한다).
   */
  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String key = apiKey.get();
    if (StringUtils.isBlank(key)) {
      return execution.execute(request, body);
    }
    // new HttpHeaders(HttpHeaders) 는 원본을 감싸므로(공유) 반드시 copyOf 로 복사한다
    HttpHeaders headers = HttpHeaders.copyOf(request.getHeaders());
    headers.set(headerName, headerValue.apply(key));
    return execution.execute(new HttpRequestWrapper(request) {
      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    }, body);
  }
}

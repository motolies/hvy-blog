package kr.hvy.blog.modules.advisor.client.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicIoException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Anthropic SDK 의 전송 계층({@link HttpClient})을 이 저장소의 {@link RestClient} 로 구현한 어댑터(2026-11-11).
 * <p>
 * SDK 의 타입·예외·직렬화는 그대로 쓰되 HTTP 만 {@code claudeApiRestClient} 로 보낸다 — 호출 1건이 {@code ApiLogInterceptor} 를 지나
 * {@code tb_api_log} 1행이 되게 하기 위해서다(SDK 의 OkHttp 모듈을 쓰면 api_log 를 우회한다).
 * <ul>
 *   <li><b>인증 헤더는 싣지 않는다</b> — SDK 가 붙인 {@code x-api-key}·{@code authorization} 은 버리고, 키는 RestClient 의
 *       인증 인터셉터({@code ApiKeyHeaderInterceptor})가 api_log 인터셉터 뒤에서 헤더 사본에만 넣는다. 원본 요청 헤더에 키가 있으면
 *       {@code tb_api_log.request_header} 에 그대로 저장된다.</li>
 *   <li><b>상태 코드를 해석하지 않는다</b> — {@code exchange()} 로 4xx/5xx 도 본문째 돌려준다. 오류 상태를 예외로 바꾸는 것은 SDK 몫이고,
 *       이 저장소 RestClient 의 기본 status handler 는 오류를 로그만 남기고 삼키므로 {@code retrieve()} 를 쓰면 SDK 가 오류를 보지 못한다.</li>
 *   <li><b>{@link RequestOptions} 의 타임아웃은 무시한다</b> — 응답 타임아웃은 RestClient(HttpClient5 RequestConfig)에 빈 생성 시점에 고정된
 *       {@code advisor.claude.timeout-seconds} 가 지배한다. SDK 가 넘기는 값은 클라이언트 기본값(10분)인데, 잡의 실행 창은 yml 값을 기준으로
 *       잡혀 있어 그보다 길게 기다리면 안 되고, RestClient 는 호출마다 타임아웃을 바꾸는 수단도 주지 않는다.</li>
 * </ul>
 * 재시도는 하지 않는다({@link ClaudeMessagesClient} 가 한다). 비동기 호출은 지원하지 않는다.
 */
public class RestClientHttpClient implements HttpClient {

  /** Anthropic API 키 헤더 이름 — RestClient 의 인증 인터셉터가 이 헤더에 키를 싣는다 */
  public static final String API_KEY_HEADER = "x-api-key";
  /** 원본 요청에 실으면 안 되는 인증 헤더(소문자). 비교는 대소문자를 가리지 않는다 */
  private static final Set<String> CREDENTIAL_HEADERS = Set.of(API_KEY_HEADER, "authorization");

  private final RestClient restClient;

  /**
   * @param restClient 전송에 쓰는 RestClient — api_log 인터셉터와 인증 인터셉터가 붙어 있고 응답 타임아웃이 고정돼 있어야 한다
   */
  public RestClientHttpClient(RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * 이 어댑터로 전송하는 SDK 클라이언트를 만든다. SDK 에는 <b>자격 증명을 주지 않는다</b>(키는 RestClient 인터셉터가 넣는다) — 주지 않으면 SDK 는
   * 인증 헤더를 붙이지 않는다. SDK 재시도는 끈다({@code maxRetries(0)}): SDK 재시도는 Retry-After 를 상한 없이 기다리고 타임아웃도 재시도해
   * 잡의 실행 창을 넘기므로 {@link ClaudeMessagesClient} 가 대신한다.
   */
  public static AnthropicClient anthropicClient(RestClient restClient) {
    return new AnthropicClientImpl(ClientOptions.builder()
        .httpClient(new RestClientHttpClient(restClient))
        .maxRetries(0)
        .build());
  }

  /**
   * SDK 요청 1건을 RestClient 호출 1건으로 옮기고, 상태 코드와 무관하게 응답 전체를 읽어 돌려준다.
   * URL 은 SDK 가 인코딩을 끝낸 값이라 {@link URI} 로 넘긴다 — String 오버로드는 URI 템플릿으로 다시 확장·인코딩한다.
   *
   * @throws AnthropicIoException 연결 실패·타임아웃(Spring {@link ResourceAccessException})
   */
  @Override
  public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
    try {
      RestClient.RequestBodySpec spec = restClient.method(HttpMethod.valueOf(request.method().name()))
          .uri(URI.create(request.url()))
          .headers(headers -> copyRequestHeaders(request.headers(), headers));
      request.body().ifPresent(body -> writeBody(spec, body));
      return spec.exchange((req, res) ->
          new BufferedResponse(res.getStatusCode().value(), toSdkHeaders(res.getHeaders()), res.getBody().readAllBytes()));
    } catch (ResourceAccessException e) {
      // api_log 에는 ApiLogInterceptor 가 [no response] 로 이미 남겼다
      throw new AnthropicIoException(e.getMessage(), e);
    } finally {
      request.body().ifPresent(HttpRequestBody::close);
    }
  }

  /**
   * 비동기 전송은 지원하지 않는다 — advisor 는 동기 호출만 쓰고, 풀·스레드를 따로 두지 않기 위해서다.
   */
  @Override
  public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("RestClientHttpClient 는 동기 호출만 지원한다"));
  }

  /**
   * 닫을 것이 없다 — RestClient(커넥션 풀)는 스프링 컨테이너가 관리한다.
   */
  @Override
  public void close() {
  }

  /**
   * SDK 가 붙인 헤더(anthropic-version·User-Agent·X-Stainless-* 등)를 그대로 옮기되 인증 헤더는 뺀다.
   */
  private static void copyRequestHeaders(Headers source, HttpHeaders target) {
    for (String name : source.names()) {
      if (!CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        target.put(name, new ArrayList<>(source.values(name)));
      }
    }
  }

  /**
   * 본문을 바이트로 만들어 싣는다. Content-Type 은 SDK 본문의 것을 쓴다(헤더 목록에는 없다).
   */
  private static void writeBody(RestClient.RequestBodySpec spec, HttpRequestBody body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    String contentType = body.contentType();
    if (contentType != null) {
      spec.contentType(MediaType.parseMediaType(contentType));
    }
    spec.body(out.toByteArray());
  }

  /**
   * 응답 헤더를 SDK 헤더로 옮긴다 — SDK 가 request-id 와 Retry-After 를 여기서 읽는다.
   */
  private static Headers toSdkHeaders(HttpHeaders headers) {
    Headers.Builder builder = Headers.builder();
    headers.forEach((name, values) -> builder.put(name, values));
    return builder.build();
  }

  /**
   * 본문을 미리 읽어 둔 응답. RestClient 의 {@code exchange} 가 끝나면 원본 응답이 닫히므로 SDK 에는 메모리 사본을 넘긴다.
   */
  private record BufferedResponse(int statusCode, Headers headers, byte[] bytes) implements HttpResponse {

    @Override
    public InputStream body() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void close() {
    }
  }
}

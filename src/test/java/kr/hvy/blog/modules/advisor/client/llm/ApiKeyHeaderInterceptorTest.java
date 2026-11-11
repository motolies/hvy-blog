package kr.hvy.blog.modules.advisor.client.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * 인증 인터셉터 계약 — 실행에 넘어가는 요청에는 키가 있고, 앞 단계(api_log 인터셉터)가 들고 있는 원본 요청 헤더에는 없어야 한다.
 * OpenAI(Bearer)와 Anthropic(x-api-key) 두 형태를 같은 구현이 맡는다.
 */
@DisplayName("ApiKeyHeaderInterceptor - 헤더 사본에만 API 키")
class ApiKeyHeaderInterceptorTest {

  private static final URI OPENAI = URI.create("https://api.openai.com/v1/responses");
  private static final URI ANTHROPIC = URI.create("https://api.anthropic.com/v1/messages");
  private static final String API_KEY_HEADER = "x-api-key";

  /** 실행 단계로 넘어간 요청을 잡아 두는 실행기 */
  private static ClientHttpRequestExecution capturing(AtomicReference<HttpRequest> executed) {
    return (request, body) -> {
      executed.set(request);
      return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    };
  }

  @Test
  @DisplayName("bearer: 실행 요청에는 'Authorization: Bearer <키>' 가 있고 원본 요청 헤더에는 없다")
  void 사본에만_Bearer() throws IOException {
    ApiKeyHeaderInterceptor interceptor = ApiKeyHeaderInterceptor.bearer(() -> "sk-test");
    MockClientHttpRequest original = new MockClientHttpRequest(HttpMethod.POST, OPENAI);
    original.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    AtomicReference<HttpRequest> executed = new AtomicReference<>();

    interceptor.intercept(original, new byte[0], capturing(executed));

    assertThat(executed.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");
    assertThat(executed.get().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(executed.get().getURI()).isEqualTo(OPENAI);
    assertThat(original.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).as("api_log 가 저장하는 원본 헤더").isNull();
  }

  @Test
  @DisplayName("header: 실행 요청에는 지정한 헤더에 키가 그대로 있고 원본 요청 헤더에는 없다 — 나머지 헤더·URI 는 그대로다")
  void 사본에만_키() throws IOException {
    ApiKeyHeaderInterceptor interceptor = ApiKeyHeaderInterceptor.header(API_KEY_HEADER, () -> "sk-ant-test");
    MockClientHttpRequest original = new MockClientHttpRequest(HttpMethod.POST, ANTHROPIC);
    original.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    original.getHeaders().add("anthropic-version", "2026-10-21");
    AtomicReference<HttpRequest> executed = new AtomicReference<>();

    interceptor.intercept(original, new byte[0], capturing(executed));

    assertThat(executed.get().getHeaders().getFirst(API_KEY_HEADER)).isEqualTo("sk-ant-test");
    assertThat(executed.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).as("Bearer 형식이 섞이지 않는다").isNull();
    assertThat(executed.get().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(executed.get().getHeaders().getFirst("anthropic-version")).isEqualTo("2026-10-21");
    assertThat(executed.get().getURI()).isEqualTo(ANTHROPIC);
    assertThat(original.getHeaders().getFirst(API_KEY_HEADER)).as("api_log 가 저장하는 원본 헤더").isNull();
  }

  @Test
  @DisplayName("키는 호출할 때마다 읽는다 — 기동 뒤에 바뀐 값이 다음 요청에 실린다")
  void 키는_호출시점에_읽는다() throws IOException {
    AtomicReference<String> key = new AtomicReference<>("sk-ant-old");
    ApiKeyHeaderInterceptor interceptor = ApiKeyHeaderInterceptor.header(API_KEY_HEADER, key::get);
    AtomicReference<HttpRequest> executed = new AtomicReference<>();

    key.set("sk-ant-new");
    interceptor.intercept(new MockClientHttpRequest(HttpMethod.POST, ANTHROPIC), new byte[0], capturing(executed));

    assertThat(executed.get().getHeaders().getFirst(API_KEY_HEADER)).isEqualTo("sk-ant-new");
  }

  @Test
  @DisplayName("키가 비어 있으면 헤더를 넣지 않고 원본 요청을 그대로 넘긴다(두 형태 모두)")
  void 빈키는_그대로() throws IOException {
    for (ApiKeyHeaderInterceptor interceptor : new ApiKeyHeaderInterceptor[] {
        ApiKeyHeaderInterceptor.bearer(() -> " "), ApiKeyHeaderInterceptor.header(API_KEY_HEADER, () -> null)}) {
      MockClientHttpRequest original = new MockClientHttpRequest(HttpMethod.POST, OPENAI);
      AtomicReference<HttpRequest> executed = new AtomicReference<>();

      interceptor.intercept(original, new byte[0], capturing(executed));

      assertThat(executed.get()).isSameAs(original);
      assertThat(executed.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
      assertThat(executed.get().getHeaders().getFirst(API_KEY_HEADER)).isNull();
    }
  }
}

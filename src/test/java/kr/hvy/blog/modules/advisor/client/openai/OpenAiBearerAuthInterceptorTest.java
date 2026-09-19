package kr.hvy.blog.modules.advisor.client.openai;

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
 * 인증 인터셉터 계약 — 실행에 넘어가는 요청에는 Bearer 가 있고, 앞 단계(api_log 인터셉터)가 들고 있는 원본 요청 헤더에는 없어야 한다.
 */
@DisplayName("OpenAiBearerAuthInterceptor - 헤더 사본에만 Bearer")
class OpenAiBearerAuthInterceptorTest {

  private static final URI TARGET = URI.create("https://api.openai.com/v1/responses");

  @Test
  @DisplayName("실행 요청에는 Authorization 이 있고 원본 요청 헤더에는 없다")
  void 사본에만_Bearer() throws IOException {
    OpenAiBearerAuthInterceptor interceptor = new OpenAiBearerAuthInterceptor(() -> "sk-test");
    MockClientHttpRequest original = new MockClientHttpRequest(HttpMethod.POST, TARGET);
    original.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    AtomicReference<HttpRequest> executed = new AtomicReference<>();
    ClientHttpRequestExecution execution = (request, body) -> {
      executed.set(request);
      return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    };

    interceptor.intercept(original, new byte[0], execution);

    assertThat(executed.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");
    assertThat(executed.get().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(executed.get().getURI()).isEqualTo(TARGET);
    assertThat(original.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).as("api_log 가 저장하는 원본 헤더").isNull();
  }

  @Test
  @DisplayName("키가 비어 있으면 헤더를 넣지 않고 그대로 넘긴다")
  void 빈키는_그대로() throws IOException {
    OpenAiBearerAuthInterceptor interceptor = new OpenAiBearerAuthInterceptor(() -> " ");
    MockClientHttpRequest original = new MockClientHttpRequest(HttpMethod.POST, TARGET);
    AtomicReference<HttpRequest> executed = new AtomicReference<>();
    ClientHttpRequestExecution execution = (request, body) -> {
      executed.set(request);
      return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    };

    interceptor.intercept(original, new byte[0], execution);

    assertThat(executed.get()).isSameAs(original);
    assertThat(executed.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
  }
}

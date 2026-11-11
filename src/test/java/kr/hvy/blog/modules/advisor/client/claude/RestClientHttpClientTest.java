package kr.hvy.blog.modules.advisor.client.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.models.ModelListParams;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import kr.hvy.blog.modules.advisor.client.llm.ApiKeyHeaderInterceptor;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 전송 어댑터 계약 — 실제 SDK 클라이언트가 만든 요청이 RestClient 로 그대로 나가고(인증 헤더 제외), 응답은 상태 코드와 무관하게 SDK 로 돌아간다.
 * 네트워크는 쓰지 않는다(MockRestServiceServer).
 */
@DisplayName("RestClientHttpClient - Anthropic SDK 전송을 RestClient 로")
class RestClientHttpClientTest {

  private static final String BASE_URL = "https://api.anthropic.com";
  private static final String MESSAGES_URL = BASE_URL + "/v1/messages";
  private static final String OK_BODY = """
      {"id":"msg_1","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[{"type":"text","text":"안녕"}],
       "stop_reason":"end_turn","stop_sequence":null,
       "usage":{"input_tokens":12,"output_tokens":7},
       "unknown_field":{"x":1}}
      """;
  private static final String OVERLOADED_BODY = """
      {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"},"request_id":"req_x"}
      """;

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private AnthropicClient client;

  @BeforeEach
  void setUp() {
    // 운영 RestClient(RestClientConfigurer)처럼 오류 상태를 삼키는 기본 status handler 를 둔다 — 어댑터는 이 핸들러를 타지 않아야 한다
    builder = RestClient.builder()
        .baseUrl(BASE_URL)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
        });
    server = MockRestServiceServer.bindTo(builder).build();
    client = RestClientHttpClient.anthropicClient(builder.build());
  }

  private static MessageCreateParams params() {
    return MessageCreateParams.builder().model("claude-test").maxTokens(100L).addUserMessage("보여?").build();
  }

  @Test
  @DisplayName("SDK 요청이 POST /v1/messages 로 나가고(anthropic-version 포함·인증 헤더 없음) 2xx 본문이 SDK Message 로 파싱된다")
  void 정상호출_요청과_파싱() {
    server.expect(requestTo(MESSAGES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("anthropic-version", Matchers.not(Matchers.blankOrNullString())))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
        .andExpect(headerDoesNotExist("x-api-key"))
        .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        .andExpect(jsonPath("$.model").value("claude-test"))
        .andExpect(jsonPath("$.max_tokens").value(100))
        .andExpect(jsonPath("$.messages[0].role").value("user"))
        .andExpect(jsonPath("$.messages[0].content").value("보여?"))
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    Message message = client.messages().create(params());

    server.verify();
    assertThat(message.id()).isEqualTo("msg_1");
    assertThat(message.model().asString()).isEqualTo("claude-test-2026");
    assertThat(message.content()).hasSize(1);
    assertThat(message.content().get(0).text()).hasValueSatisfying(text -> assertThat(text.text()).isEqualTo("안녕"));
    assertThat(message.stopReason()).contains(StopReason.END_TURN);
    assertThat(message.usage().inputTokens()).isEqualTo(12);
    assertThat(message.usage().outputTokens()).isEqualTo(7);
  }

  @Test
  @DisplayName("SDK 요청에 인증 헤더가 실려 와도(대소문자 무관) 버리고, 다른 헤더는 그대로 옮긴다")
  void 인증헤더는_싣지않는다() {
    AnthropicClient withCredentialHeaders = client.withOptions(options -> options
        .putHeader("X-Api-Key", "sk-ant-leak")
        .putHeader("AUTHORIZATION", "Bearer leak")
        .putHeader("X-Advisor-Probe", "kept"));
    server.expect(requestTo(MESSAGES_URL))
        .andExpect(headerDoesNotExist("x-api-key"))
        .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        .andExpect(header("X-Advisor-Probe", "kept"))
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    withCredentialHeaders.messages().create(params());

    server.verify();
  }

  @Test
  @DisplayName("키는 뒤 단계 인터셉터가 넣으므로 api_log 자리의 인터셉터가 본 요청 헤더에는 x-api-key 가 없고, 실제 전송에는 있다")
  void 키는_로그인터셉터에_보이지않는다() {
    List<HttpHeaders> seenByLogInterceptor = new ArrayList<>();
    RestClient.Builder chained = RestClient.builder()
        .baseUrl(BASE_URL)
        // ApiLogInterceptor 처럼 실행이 끝난 뒤 자기가 받은 원본 요청의 헤더를 읽는다
        .requestInterceptor((request, body, execution) -> {
          ClientHttpResponse response = execution.execute(request, body);
          seenByLogInterceptor.add(HttpHeaders.copyOf(request.getHeaders()));
          return response;
        })
        .requestInterceptor(ApiKeyHeaderInterceptor.header(RestClientHttpClient.API_KEY_HEADER, () -> "sk-ant-test"));
    MockRestServiceServer chainedServer = MockRestServiceServer.bindTo(chained).build();
    chainedServer.expect(requestTo(MESSAGES_URL))
        .andExpect(header("x-api-key", "sk-ant-test"))
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    RestClientHttpClient.anthropicClient(chained.build()).messages().create(params());

    chainedServer.verify();
    assertThat(seenByLogInterceptor).hasSize(1);
    assertThat(seenByLogInterceptor.get(0).containsHeader("x-api-key")).as("tb_api_log.request_header 에 남는 헤더").isFalse();
    assertThat(seenByLogInterceptor.get(0).containsHeader("anthropic-version")).isTrue();
  }

  @Test
  @DisplayName("SDK 가 인코딩한 URL 을 다시 인코딩하지 않는다 — 쿼리 값이 한 번만 디코딩해도 원문이다(본문 없는 GET)")
  void URL은_재인코딩하지않는다() {
    String afterId = "a b/{x}%&=";
    AtomicReference<URI> requested = new AtomicReference<>();
    server.expect(method(HttpMethod.GET))
        .andRespond(request -> {
          requested.set(request.getURI());
          return withSuccess("{\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}", MediaType.APPLICATION_JSON)
              .createResponse(request);
        });

    client.models().list(ModelListParams.builder().afterId(afterId).limit(5L).build());

    server.verify();
    assertThat(requested.get().getHost()).isEqualTo("api.anthropic.com");
    assertThat(requested.get().getPath()).isEqualTo("/v1/models");
    String rawAfterId = UriComponentsBuilder.fromUri(requested.get()).build(true).getQueryParams().getFirst("after_id");
    assertThat(URLDecoder.decode(rawAfterId, StandardCharsets.UTF_8)).isEqualTo(afterId);
  }

  @Test
  @DisplayName("529 는 삼키지 않고 본문·헤더째 SDK 로 넘겨 상태 코드를 보존한 SDK 예외가 된다")
  void 오류상태는_SDK예외로() {
    HttpHeaders retryAfter = new HttpHeaders();
    retryAfter.add("Retry-After", "5");
    server.expect(requestTo(MESSAGES_URL))
        .andRespond(withStatus(HttpStatusCode.valueOf(529)).headers(retryAfter).contentType(MediaType.APPLICATION_JSON).body(OVERLOADED_BODY));

    assertThatThrownBy(() -> client.messages().create(params()))
        .isInstanceOfSatisfying(AnthropicServiceException.class, e -> {
          assertThat(e.statusCode()).isEqualTo(529);
          assertThat(e.apiMessage()).contains("Overloaded");
          assertThat(e.headers().values("retry-after")).containsExactly("5");
        });
    server.verify();
  }

  @Test
  @DisplayName("4xx 도 RestClient 기본 status handler 에 먹히지 않고 SDK 예외가 된다")
  void 사백도_SDK예외로() {
    server.expect(requestTo(MESSAGES_URL))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
            .body("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: too large\"}}"));

    assertThatThrownBy(() -> client.messages().create(params()))
        .isInstanceOfSatisfying(AnthropicServiceException.class, e -> {
          assertThat(e.statusCode()).isEqualTo(400);
          assertThat(e.apiMessage()).contains("max_tokens: too large");
        });
  }

  @Test
  @DisplayName("연결 실패·타임아웃(ResourceAccessException)은 AnthropicIoException 으로 감싼다")
  void 연결실패는_IoException() {
    server.expect(requestTo(MESSAGES_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertThatThrownBy(() -> client.messages().create(params()))
        .isInstanceOf(AnthropicIoException.class)
        .hasMessageContaining("Read timed out")
        .hasCauseInstanceOf(ResourceAccessException.class);
  }

  @Test
  @DisplayName("비동기 전송은 지원하지 않는다 — 요청을 보내지 않고 실패한 future 를 돌려준다")
  void 비동기는_미지원() {
    HttpRequest request = HttpRequest.builder()
        .method(com.anthropic.core.http.HttpMethod.GET)
        .baseUrl(BASE_URL)
        .addPathSegments("v1", "models")
        .build();

    CompletableFuture<HttpResponse> future = new RestClientHttpClient(builder.build()).executeAsync(request, RequestOptions.none());

    assertThat(future).isCompletedExceptionally();
    assertThatThrownBy(future::get)
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(UnsupportedOperationException.class);
    server.verify();
  }
}

package kr.hvy.blog.modules.advisor.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.advisor.client.openai.dto.InputMessage;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * HTTP 계층 계약 — 요청 JSON 형태, 2xx 파싱, 429(Retry-After)·5xx 재시도, 4xx 즉시 실패. 대기는 Sleeper 로 기록만 한다.
 */
@DisplayName("OpenAiResponsesClient - /v1/responses 호출·재시도·상태 분기")
class OpenAiResponsesClientTest {

  private static final String URL = "https://api.openai.com/v1/responses";
  private static final String OK_BODY = """
      {"id":"resp_1","object":"response","status":"completed","model":"gpt-test-2026",
       "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"안녕","annotations":[]}]}],
       "usage":{"input_tokens":12,"output_tokens":7,"total_tokens":19,
                "input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":5}},
       "unknown_field":{"x":1}}
      """;
  private static final String ERROR_400 = """
      {"error":{"message":"Function tools with reasoning_effort are not supported","type":"invalid_request_error","code":"unsupported_parameter"}}
      """;

  private MockRestServiceServer server;
  private List<Long> sleeps;
  private OpenAiResponsesClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
    server = MockRestServiceServer.bindTo(builder).build();
    sleeps = new ArrayList<>();
    client = new OpenAiResponsesClient(builder.build(), 2, sleeps::add);
  }

  private static ResponsesRequest request() {
    return ResponsesRequest.builder()
        .model("gpt-test")
        .instructions("시스템")
        .input(List.of(new InputMessage(InputMessage.ROLE_USER, "보여?")))
        .maxOutputTokens(100)
        .store(Boolean.FALSE)
        .include(List.of("reasoning.encrypted_content"))
        .build();
  }

  @Test
  @DisplayName("요청은 snake_case JSON(null 생략)이고 2xx 본문을 파싱한다 — 미지 필드는 무시")
  void 정상호출_파싱() {
    server.expect(requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.CONTENT_TYPE, Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
        .andExpect(jsonPath("$.model").value("gpt-test"))
        .andExpect(jsonPath("$.instructions").value("시스템"))
        .andExpect(jsonPath("$.input[0].role").value("user"))
        .andExpect(jsonPath("$.max_output_tokens").value(100))
        .andExpect(jsonPath("$.store").value(false))
        .andExpect(jsonPath("$.include[0]").value("reasoning.encrypted_content"))
        .andExpect(jsonPath("$.temperature").doesNotExist())
        .andExpect(jsonPath("$.tools").doesNotExist())
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    ResponsesResponse response = client.create(request());

    server.verify();
    assertThat(response.id()).isEqualTo("resp_1");
    assertThat(response.status()).isEqualTo(ResponsesResponse.STATUS_COMPLETED);
    assertThat(response.model()).isEqualTo("gpt-test-2026");
    assertThat(response.output()).hasSize(1);
    assertThat(response.output().get(0).path("content").path(0).path("text").asString()).isEqualTo("안녕");
    assertThat(response.usage().inputTokens()).isEqualTo(12);
    assertThat(response.usage().reasoningTokens()).isEqualTo(5);
    assertThat(response.usage().cachedTokens()).isEqualTo(4);
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("429 는 Retry-After(초)만큼 대기한 뒤 재시도한다 — 시도 1회 = 호출 1회")
  void 재시도_RetryAfter() {
    HttpHeaders retryAfter = new HttpHeaders();
    retryAfter.add("Retry-After", "2");
    server.expect(ExpectedCount.once(), requestTo(URL))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit\"}}"));
    server.expect(ExpectedCount.once(), requestTo(URL))
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    ResponsesResponse response = client.create(request());

    server.verify();
    assertThat(response.id()).isEqualTo("resp_1");
    assertThat(sleeps).containsExactly(2_000L);
  }

  @Test
  @DisplayName("400 은 재시도 없이 즉시 실패하고 메시지는 '400: <OpenAI 메시지>' 다")
  void 사백은_즉시실패() {
    server.expect(ExpectedCount.once(), requestTo(URL))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(ERROR_400));

    assertThatThrownBy(() -> client.create(request()))
        .isInstanceOf(OpenAiResponsesException.class)
        .hasMessage("400: Function tools with reasoning_effort are not supported")
        .satisfies(e -> {
          OpenAiResponsesException ex = (OpenAiResponsesException) e;
          assertThat(ex.getStatus()).isEqualTo(400);
          assertThat(ex.getCode()).isEqualTo("unsupported_parameter");
          assertThat(ex.isRetryable()).isFalse();
        });
    server.verify();
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("5xx 는 maxRetries 만큼 지수 백오프로 재시도하고 소진되면 마지막 상태로 실패한다")
  void 오백_소진() {
    server.expect(ExpectedCount.times(3), requestTo(URL))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN).body("upstream down"));

    assertThatThrownBy(() -> client.create(request()))
        .isInstanceOf(OpenAiResponsesException.class)
        .hasMessage("503: upstream down")
        .satisfies(e -> assertThat(((OpenAiResponsesException) e).isRetryable()).isTrue());
    server.verify();
    assertThat(sleeps).hasSize(2);
    assertThat(sleeps.get(0)).isBetween(500L, 750L);
    assertThat(sleeps.get(1)).isBetween(1_000L, 1_250L);
  }

  @Test
  @DisplayName("2xx 본문이 JSON 이 아니면 파싱 실패로 즉시 실패한다")
  void 파싱실패() {
    server.expect(ExpectedCount.once(), requestTo(URL))
        .andRespond(withSuccess("<html>not json</html>", MediaType.TEXT_HTML));

    assertThatThrownBy(() -> client.create(request()))
        .isInstanceOf(OpenAiResponsesException.class)
        .hasMessageStartingWith("200: 응답 파싱 실패");
    assertThat(sleeps).isEmpty();
  }
}

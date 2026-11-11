package kr.hvy.blog.modules.advisor.client.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorDetails;
import com.anthropic.models.messages.MessageCreateParams;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * 오류 분류 계약. SDK 예외는 손으로 만들지 않고 <b>실제 SDK 파싱</b>(MockRestServiceServer 응답 → SDK 오류 핸들러)을 거쳐 얻는다 —
 * {@code apiMessage()}·{@code errorCode()} 가 본문의 어느 자리를 읽는지까지 함께 고정하기 위해서다.
 */
@DisplayName("ClaudeErrors - SDK 예외 → LlmProviderException 분류")
class ClaudeErrorsTest {

  private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
  private static final String SPEND_LIMIT_429 = """
      {"type":"error","error":{"type":"rate_limit_error","message":"This request would exceed your organization's spend limit.",
       "details":{"error_code":"enforced_spend_limit_reached"}},"request_id":"req_x"}
      """;

  private static String errorBody(String type, String message) {
    return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"},\"request_id\":\"req_x\"}";
  }

  /** 응답 1건을 실제 SDK 클라이언트로 받아 SDK 가 던진 예외를 돌려준다 */
  private static Exception sdkFailure(ResponseCreator response) {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.anthropic.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(MESSAGES_URL)).andRespond(response);
    AnthropicClient client = RestClientHttpClient.anthropicClient(builder.build());
    MessageCreateParams params = MessageCreateParams.builder().model("claude-test").maxTokens(100L).addUserMessage("보여?").build();
    return catchThrowableOfType(Exception.class, () -> client.messages().create(params));
  }

  private static DefaultResponseCreator jsonError(int status, String body) {
    return withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  static Stream<Arguments> classificationTable() {
    return Stream.of(
        // EXHAUSTED — 기다려도 풀리지 않는 소진
        Arguments.of("402 결제 문제", 402, errorBody("billing_error", "Your account has a billing problem."), LlmFailureKind.EXHAUSTED),
        Arguments.of("400 지출 한도 문구", 400,
            errorBody("invalid_request_error", "You have reached your specified API usage limits. You will regain access on 2026-12-01 at 00:00 UTC."),
            LlmFailureKind.EXHAUSTED),
        Arguments.of("400 크레딧 소진 문구(과거 형태)", 400,
            errorBody("invalid_request_error", "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing."),
            LlmFailureKind.EXHAUSTED),
        Arguments.of("429 지출 한도 강제", 429, SPEND_LIMIT_429, LlmFailureKind.EXHAUSTED),
        Arguments.of("403 인데 유형이 billing_error(상태 무관)", 403, errorBody("billing_error", "Your account has a billing problem."),
            LlmFailureKind.EXHAUSTED),
        Arguments.of("400 인데 유형이 billing_error(문구 무관)", 400, errorBody("billing_error", "Payment is required to continue."),
            LlmFailureKind.EXHAUSTED),
        // MISCONFIGURED — 사람이 고쳐야 하는 키·권한·모델 ID
        Arguments.of("401 키 오류", 401, errorBody("authentication_error", "invalid x-api-key"), LlmFailureKind.MISCONFIGURED),
        Arguments.of("403 권한 없음", 403, errorBody("permission_error", "Not allowed for this credential"), LlmFailureKind.MISCONFIGURED),
        Arguments.of("404 모델 없음", 404, errorBody("not_found_error", "model: claude-nope"), LlmFailureKind.MISCONFIGURED),
        // UNAVAILABLE — 일시 장애
        Arguments.of("429 rate limit", 429, errorBody("rate_limit_error", "Number of request tokens has exceeded your per-minute rate limit"),
            LlmFailureKind.UNAVAILABLE),
        Arguments.of("429 다른 error_code", 429,
            "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"ramping\",\"details\":{\"error_code\":\"ramp_limit_exceeded\"}}}",
            LlmFailureKind.UNAVAILABLE),
        Arguments.of("408 요청 타임아웃", 408, errorBody("timeout_error", "Request timed out"), LlmFailureKind.UNAVAILABLE),
        Arguments.of("409 충돌", 409, errorBody("conflict_error", "Conflict"), LlmFailureKind.UNAVAILABLE),
        Arguments.of("500 서버 오류", 500, errorBody("api_error", "Internal server error"), LlmFailureKind.UNAVAILABLE),
        Arguments.of("502 게이트웨이(HTML 본문)", 502, "<html>bad gateway</html>", LlmFailureKind.UNAVAILABLE),
        Arguments.of("503 서비스 불가", 503, errorBody("api_error", "Service unavailable"), LlmFailureKind.UNAVAILABLE),
        Arguments.of("504 게이트웨이 타임아웃", 504, errorBody("timeout_error", "Gateway timeout"), LlmFailureKind.UNAVAILABLE),
        Arguments.of("529 과부하", 529, errorBody("overloaded_error", "Overloaded"), LlmFailureKind.UNAVAILABLE),
        // REQUEST_INVALID — 요청 자체의 오류(폴백하지 않는다)
        Arguments.of("400 요청 형식 오류", 400, errorBody("invalid_request_error", "messages: roles must alternate"), LlmFailureKind.REQUEST_INVALID),
        Arguments.of("400 지출 한도 문구가 머리말이 아님", 400,
            errorBody("invalid_request_error", "system: You have reached your specified limit of cache breakpoints"), LlmFailureKind.REQUEST_INVALID),
        Arguments.of("413 요청 과대", 413, errorBody("request_too_large", "Request exceeds the maximum allowed number of bytes"),
            LlmFailureKind.REQUEST_INVALID),
        Arguments.of("422 처리 불가", 422, errorBody("invalid_request_error", "Unprocessable"), LlmFailureKind.REQUEST_INVALID),
        Arguments.of("405 기타 4xx", 405, errorBody("invalid_request_error", "Method not allowed"), LlmFailureKind.REQUEST_INVALID));
  }

  @ParameterizedTest(name = "{0} → {3}")
  @MethodSource("classificationTable")
  @DisplayName("상태 코드와 오류 본문으로 분류한다 — 상태·공급자·원인 예외를 보존한다")
  void 분류표(String label, int status, String body, LlmFailureKind expected) {
    Exception sdkError = sdkFailure(jsonError(status, body));

    LlmProviderException classified = ClaudeErrors.classify(sdkError);

    assertThat(sdkError).isInstanceOf(AnthropicServiceException.class);
    assertThat(classified.getKind()).as(label).isEqualTo(expected);
    assertThat(classified.getStatus()).isEqualTo(status);
    assertThat(classified.getProvider()).isEqualTo(LlmProvider.CLAUDE);
    assertThat(classified.getRetryAfterSeconds()).isNull();
    assertThat(classified).hasCause(sdkError);
  }

  @Test
  @DisplayName("지출 한도 429 — SDK errorCode() 는 details.error_code 문자열 'enforced_spend_limit_reached' 를 그대로 준다(실측 고정)")
  void 지출한도_errorCode_실측() {
    HttpHeaders retryAfter = new HttpHeaders();
    retryAfter.add("Retry-After", "3600");

    Exception sdkError = sdkFailure(jsonError(429, SPEND_LIMIT_429).headers(retryAfter));

    assertThat(sdkError).isInstanceOfSatisfying(AnthropicServiceException.class, e -> {
      assertThat(e.statusCode()).isEqualTo(429);
      assertThat(e.errorCode()).contains("enforced_spend_limit_reached");
    });
    assertThat(ClaudeErrors.ENFORCED_SPEND_LIMIT_CODE)
        .as("SDK 상수와 같은 문자열")
        .isEqualTo(ErrorDetails.ErrorCode.ENFORCED_SPEND_LIMIT_REACHED.asString());
    LlmProviderException classified = ClaudeErrors.classify(sdkError);
    assertThat(classified.getKind()).isEqualTo(LlmFailureKind.EXHAUSTED);
    assertThat(classified.getRetryAfterSeconds()).isEqualTo(3600L);
  }

  @Test
  @DisplayName("메시지는 '<공급자> <상태>: <API 오류 문구>' — 문구는 300자로 줄인다")
  void 메시지는_API문구를_줄여싣는다() {
    LlmProviderException shortMessage = ClaudeErrors.classify(sdkFailure(jsonError(529, errorBody("overloaded_error", "Overloaded"))));
    LlmProviderException longMessage = ClaudeErrors.classify(sdkFailure(jsonError(400, errorBody("invalid_request_error", "x".repeat(1_000)))));

    assertThat(shortMessage).hasMessage("CLAUDE 529: Overloaded");
    assertThat(longMessage.getMessage()).startsWith("CLAUDE 400: xxx").hasSize("CLAUDE 400: ".length() + 300);
  }

  @Test
  @DisplayName("오류 본문이 JSON 이 아니면 본문 머리를, 비어 있으면 '오류 본문 없음' 을 싣는다")
  void JSON이_아닌_오류본문() {
    LlmProviderException html = ClaudeErrors.classify(
        sdkFailure(withStatus(HttpStatusCode.valueOf(502)).contentType(MediaType.TEXT_HTML).body("<html>bad gateway</html>")));
    LlmProviderException empty = ClaudeErrors.classify(sdkFailure(withStatus(HttpStatusCode.valueOf(503))));

    assertThat(html).hasMessage("CLAUDE 502: <html>bad gateway</html>");
    assertThat(html.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
    assertThat(empty).hasMessage("CLAUDE 503: 오류 본문 없음");
    assertThat(empty.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
  }

  @ParameterizedTest(name = "Retry-After ''{0}'' → {1}")
  @CsvSource(value = {"12, 12", "' 3 ', 3", "0, 0", "-5, 0", "'Wed, 21 Oct 2026 07:28:00 GMT', NULL", "1.5, NULL", "'', NULL"}, nullValues = "NULL")
  @DisplayName("Retry-After 는 초 단위 정수만 읽는다(헤더 이름은 대소문자 무관) — 그 밖의 형식은 null")
  void retryAfter_해석(String header, Long expected) {
    assertThat(ClaudeErrors.retryAfterSeconds(Headers.builder().put("Retry-After", header).build())).isEqualTo(expected);
  }

  @Test
  @DisplayName("Retry-After 헤더가 없으면 null")
  void retryAfter_없음() {
    assertThat(ClaudeErrors.retryAfterSeconds(Headers.builder().build())).isNull();
  }

  @Test
  @DisplayName("타임아웃·연결 실패(AnthropicIoException)는 UNAVAILABLE, status 0")
  void 전송실패는_UNAVAILABLE_0() {
    Exception sdkError = sdkFailure(withException(new SocketTimeoutException("Read timed out")));

    LlmProviderException classified = ClaudeErrors.classify(sdkError);

    assertThat(sdkError).isInstanceOf(AnthropicIoException.class);
    assertThat(classified.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
    assertThat(classified.getStatus()).isZero();
    assertThat(classified.getRetryAfterSeconds()).isNull();
    assertThat(classified).hasMessageStartingWith("CLAUDE 0: 응답 없음(타임아웃·연결 실패)").hasMessageContaining("Read timed out").hasCause(sdkError);
  }

  @Test
  @DisplayName("2xx 본문을 해석하지 못하면(AnthropicInvalidDataException) UNAVAILABLE, status 0")
  void 응답해석실패는_UNAVAILABLE_0() {
    Exception sdkError = sdkFailure(withStatus(HttpStatusCode.valueOf(200)).contentType(MediaType.TEXT_HTML).body("<html>not json</html>"));

    LlmProviderException classified = ClaudeErrors.classify(sdkError);

    assertThat(sdkError).isInstanceOf(AnthropicInvalidDataException.class);
    assertThat(classified.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
    assertThat(classified.getStatus()).isZero();
    assertThat(classified).hasMessageStartingWith("CLAUDE 0: 응답 해석 실패").hasCause(sdkError);
  }

  @Test
  @DisplayName("SDK 가 던지지 않은 로컬 예외는 REQUEST_INVALID, status 0 — 일시 장애로 보면 폴백이 버그를 가리고 게이트가 'Claude 장애' 로 알린다")
  void 로컬_예외는_요청오류() {
    IllegalStateException unexpected = new IllegalStateException("boom");

    LlmProviderException classified = ClaudeErrors.classify(unexpected);

    assertThat(classified.getKind()).isEqualTo(LlmFailureKind.REQUEST_INVALID);
    assertThat(classified.getKind().isFallbackable()).isFalse();
    assertThat(classified.getStatus()).isZero();
    assertThat(classified).hasMessage("CLAUDE 0: 호출 전 로컬 오류(Claude 응답 아님): IllegalStateException: boom").hasCause(unexpected);
  }

  @Test
  @DisplayName("그 밖의 SDK 예외는 UNAVAILABLE, status 0 으로 감싼다 — 이미 분류된 예외는 그대로 돌려준다")
  void 그밖의_SDK_예외와_이미분류된_예외() {
    AnthropicRetryableException sdkError = new AnthropicRetryableException("boom");
    LlmProviderException already = new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.REQUEST_INVALID, 400, null, "이미 분류됨", null);

    LlmProviderException classified = ClaudeErrors.classify(sdkError);

    assertThat(classified.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
    assertThat(classified.getStatus()).isZero();
    assertThat(classified).hasMessage("CLAUDE 0: 호출 실패: AnthropicRetryableException: boom").hasCause(sdkError);
    assertThat(ClaudeErrors.classify(already)).isSameAs(already);
  }

  @ParameterizedTest(name = "{0} / message={1} / code={2} / type={3} → {4}")
  @CsvSource(value = {
      "302, NULL, NULL, NULL, UNAVAILABLE",
      "400, NULL, NULL, NULL, REQUEST_INVALID",
      "400, NULL, enforced_spend_limit_reached, NULL, REQUEST_INVALID",
      "429, You have reached your specified API usage limits., NULL, NULL, UNAVAILABLE",
      "400, YOUR CREDIT BALANCE IS TOO LOW, NULL, NULL, EXHAUSTED",
      "400, NULL, NULL, billing_error, EXHAUSTED",
      "529, NULL, NULL, billing_error, EXHAUSTED",
      "400, NULL, NULL, invalid_request_error, REQUEST_INVALID",
      "403, NULL, NULL, permission_error, MISCONFIGURED"}, nullValues = "NULL")
  @DisplayName("경계 — 유형이 billing_error 면 상태와 무관하게 소진, 그 밖의 소진 단서는 정해진 상태 코드에서만 본다. 4xx 가 아닌 비정상 상태는 일시 장애")
  void 분류_경계(int status, String apiMessage, String errorCode, String errorType, LlmFailureKind expected) {
    assertThat(ClaudeErrors.kindOf(status, apiMessage, errorCode, errorType)).isEqualTo(expected);
  }
}

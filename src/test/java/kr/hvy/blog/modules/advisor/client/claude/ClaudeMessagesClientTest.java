package kr.hvy.blog.modules.advisor.client.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * 재시도 계약 — 실제 SDK 클라이언트 + MockRestServiceServer 로 시도 횟수(= HTTP 호출 수)와 대기 시간을 확인한다. 대기는 Sleeper 로 기록만 하고
 * 시도 소요 시간은 주입한 시계로 흉내 낸다.
 */
@DisplayName("ClaudeMessagesClient - 재시도·대기·무재시도 조건")
class ClaudeMessagesClientTest {

  private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
  private static final String OK_BODY = """
      {"id":"msg_1","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[{"type":"text","text":"안녕"}],
       "stop_reason":"end_turn","stop_sequence":null,
       "usage":{"input_tokens":12,"output_tokens":7}}
      """;
  private static final String OVERLOADED = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
  private static final String RATE_LIMITED = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limited\"}}";

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private List<Long> sleeps;
  private AtomicLong nanos;
  private ClaudeMessagesClient client;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("https://api.anthropic.com");
    server = MockRestServiceServer.bindTo(builder).build();
    sleeps = new ArrayList<>();
    nanos = new AtomicLong();
    client = client(2);
  }

  private ClaudeMessagesClient client(int maxRetries) {
    return new ClaudeMessagesClient(RestClientHttpClient.anthropicClient(builder.build()), maxRetries, sleeps::add, nanos::get);
  }

  private static MessageCreateParams params() {
    return MessageCreateParams.builder().model("claude-test").maxTokens(100L).addUserMessage("보여?").build();
  }

  private static DefaultResponseCreator error(int status, String body) {
    return withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  private static DefaultResponseCreator error(int status, String body, String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("Retry-After", retryAfter);
    return error(status, body).headers(headers);
  }

  private static ResponseCreator ok() {
    return withSuccess(OK_BODY, MediaType.APPLICATION_JSON);
  }

  /** 응답이 오기까지 seconds 초가 걸린 것처럼 시계를 민다 */
  private ResponseCreator after(long seconds, ResponseCreator response) {
    return request -> {
      nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
      return response.createResponse(request);
    };
  }

  private void assertFails(Consumer<LlmProviderException> assertions) {
    assertThatThrownBy(() -> client.create(params()))
        .isInstanceOfSatisfying(LlmProviderException.class, e -> {
          assertThat(e.getProvider()).isEqualTo(LlmProvider.CLAUDE);
          assertions.accept(e);
        });
    server.verify();
  }

  @Test
  @DisplayName("성공하면 SDK Message 를 돌려주고 대기하지 않는다")
  void 정상호출() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(ok());

    Message message = client.create(params());

    server.verify();
    assertThat(message.id()).isEqualTo("msg_1");
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("529 는 maxRetries 만큼 지수 백오프(0.5s·2^n + 지터)로 재시도하고 소진되면 마지막 실패를 던진다 — 시도 1회 = 호출 1회")
  void 과부하_재시도_소진() {
    server.expect(ExpectedCount.times(3), requestTo(MESSAGES_URL)).andRespond(error(529, OVERLOADED));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(e.getStatus()).isEqualTo(529);
      assertThat(e).hasMessage("CLAUDE 529: Overloaded");
    });
    assertThat(sleeps).hasSize(2);
    assertThat(sleeps.get(0)).isBetween(500L, 750L);
    assertThat(sleeps.get(1)).isBetween(1_000L, 1_250L);
  }

  @Test
  @DisplayName("재시도 끝에 성공하면 Message 를 돌려준다(503 → 500 → 200)")
  void 재시도_끝에_성공() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(503, OVERLOADED));
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(500, OVERLOADED));
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(ok());

    Message message = client.create(params());

    server.verify();
    assertThat(message.id()).isEqualTo("msg_1");
    assertThat(sleeps).hasSize(2);
  }

  @Test
  @DisplayName("429 는 Retry-After(초)만큼 대기한 뒤 재시도한다")
  void 재시도_RetryAfter() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(429, RATE_LIMITED, "2"));
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(ok());

    Message message = client.create(params());

    server.verify();
    assertThat(message.id()).isEqualTo("msg_1");
    assertThat(sleeps).containsExactly(2_000L);
  }

  @Test
  @DisplayName("Retry-After 30초는 기다리고(경계), 31초면 재시도 없이 즉시 실패한다")
  void RetryAfter_상한() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(429, RATE_LIMITED, "30"));
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(429, RATE_LIMITED, "31"));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(e.getStatus()).isEqualTo(429);
      assertThat(e.getRetryAfterSeconds()).isEqualTo(31L);
    });
    assertThat(sleeps).containsExactly(30_000L);
  }

  @Test
  @DisplayName("실패한 시도가 30초 넘게 걸렸으면(느린 5xx) 재시도하지 않는다 — 30초 이하면 재시도한다")
  void 느린_실패는_재시도하지않는다() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(after(30, error(503, OVERLOADED)));
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(after(31, error(503, OVERLOADED)));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(e.getStatus()).isEqualTo(503);
    });
    assertThat(sleeps).hasSize(1);
  }

  @Test
  @DisplayName("타임아웃·연결 실패는 재시도하지 않는다(UNAVAILABLE, status 0)")
  void 타임아웃_무재시도() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(e.getStatus()).isZero();
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("2xx 본문이 JSON 이 아니면 재시도하지 않는다(UNAVAILABLE, status 0) — 이미 과금된 응답이다")
  void 응답해석실패_무재시도() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(withSuccess("<html>not json</html>", MediaType.TEXT_HTML));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(e.getStatus()).isZero();
      assertThat(e).hasMessageContaining("응답 해석 실패");
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("2xx 본문이 JSON 이어도 Message 형태가 아니면(필수 필드 없음) 속 빈 Message 를 돌려주지 않고 실패로 던진다")
  void 형태가_다른_응답은_실패() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
        .andRespond(withSuccess("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\"}", MediaType.APPLICATION_JSON));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(e.getStatus()).isZero();
      assertThat(e).hasMessageContaining("응답 해석 실패");
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("SDK 가 모르는 stop_reason·블록 종류는 실패가 아니다 — 형태 검사는 필수 필드만 본다")
  void 모르는_값은_통과() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
        .andRespond(withSuccess("""
            {"id":"msg_2","type":"message","role":"assistant","model":"claude-test-2026",
             "content":[{"type":"hologram","data":"?"}],
             "stop_reason":"brand_new_reason","stop_sequence":null,
             "usage":{"input_tokens":1,"output_tokens":2}}
            """, MediaType.APPLICATION_JSON));

    Message message = client.create(params());

    server.verify();
    assertThat(message.id()).isEqualTo("msg_2");
    assertThat(message.content()).hasSize(1);
  }

  @Test
  @DisplayName("EXHAUSTED(402·지출 한도 429)는 Retry-After 가 있어도 재시도하지 않는다")
  void 소진_무재시도() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
        .andRespond(error(402, "{\"type\":\"error\",\"error\":{\"type\":\"billing_error\",\"message\":\"billing problem\"}}"));
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
        .andRespond(error(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"spend limit\","
            + "\"details\":{\"error_code\":\"enforced_spend_limit_reached\"}}}", "1"));

    assertThatThrownBy(() -> client.create(params()))
        .isInstanceOfSatisfying(LlmProviderException.class, e -> {
          assertThat(e.getKind()).isEqualTo(LlmFailureKind.EXHAUSTED);
          assertThat(e.getStatus()).isEqualTo(402);
        });
    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.EXHAUSTED);
      assertThat(e.getStatus()).isEqualTo(429);
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("MISCONFIGURED(401)는 재시도하지 않는다")
  void 설정오류_무재시도() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
        .andRespond(error(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.MISCONFIGURED);
      assertThat(e.getStatus()).isEqualTo(401);
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("REQUEST_INVALID(400)는 재시도하지 않는다")
  void 요청오류_무재시도() {
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
        .andRespond(error(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages: roles must alternate\"}}"));

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.REQUEST_INVALID);
      assertThat(e.getStatus()).isEqualTo(400);
      assertThat(e).hasMessage("CLAUDE 400: messages: roles must alternate");
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("전송 계층에서 난 로컬 예외(SDK 예외 아님)는 REQUEST_INVALID 로 던지고 재시도하지 않는다 — 폴백 대상이 아니다")
  void 로컬예외_무재시도() {
    builder.requestInterceptor((request, body, execution) -> {
      throw new IllegalStateException("인터셉터 버그");
    });
    client = client(2);

    assertFails(e -> {
      assertThat(e.getKind()).isEqualTo(LlmFailureKind.REQUEST_INVALID);
      assertThat(e.getKind().isFallbackable()).isFalse();
      assertThat(e.getStatus()).isZero();
      assertThat(e).hasMessageContaining("로컬 오류").hasMessageContaining("인터셉터 버그").hasCauseInstanceOf(IllegalStateException.class);
    });
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("maxRetries=0 이면 일시 장애도 한 번만 호출한다")
  void 재시도_없음_설정() {
    client = client(0);
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(529, OVERLOADED));

    assertFails(e -> assertThat(e.getStatus()).isEqualTo(529));
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("재시도 대기 중 인터럽트되면 플래그를 복원하고 UNAVAILABLE 로 던진다 — 직전 실패는 suppressed 로 남는다")
  void 대기중_인터럽트() {
    client = new ClaudeMessagesClient(RestClientHttpClient.anthropicClient(builder.build()), 2, millis -> {
      throw new InterruptedException("shutdown");
    }, nanos::get);
    server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL)).andRespond(error(529, OVERLOADED));

    try {
      assertFails(e -> {
        assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
        assertThat(e.getStatus()).isZero();
        assertThat(e).hasMessageContaining("인터럽트").hasCauseInstanceOf(InterruptedException.class);
        assertThat(e.getSuppressed()).singleElement()
            .isInstanceOfSatisfying(LlmProviderException.class, last -> assertThat(last.getStatus()).isEqualTo(529));
      });
      assertThat(Thread.currentThread().isInterrupted()).as("인터럽트 플래그 복원").isTrue();
    } finally {
      // 다음 테스트로 플래그가 새지 않게 지운다
      Thread.interrupted();
    }
  }
}

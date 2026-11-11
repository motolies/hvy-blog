package kr.hvy.blog.modules.advisor.client.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import lombok.extern.slf4j.Slf4j;

/**
 * Claude Messages API 호출 1건(비스트리밍) — 재시도와 오류 분류를 맡는 얇은 클라이언트. OpenAI 쪽 {@code OpenAiResponsesClient} 와 같은 자리다.
 * <p>
 * <b>계약</b>(2026-11-11, 병렬 트랙의 접점): {@link #create(MessageCreateParams)} 는 성공하면 SDK {@link Message} 를 돌려주고,
 * 실패는 재시도를 끝낸 뒤 전부 {@link LlmProviderException}(분류 포함)으로 던진다. SDK 예외를 그대로 흘리지 않는다.
 * <p>
 * 재시도는 SDK 가 아니라 여기서 한다(SDK 는 {@code maxRetries(0)}) — SDK 재시도는 Retry-After 를 상한 없이 기다리고 지출 한도 429·타임아웃도 재시도해
 * 잡의 ShedLock 창을 넘긴다. 전송은 {@code RestClientHttpClient} 가 {@code claudeApiRestClient}(채팅 봇은 타임아웃이 짧은
 * {@code claudeChatApiRestClient})로 보내므로 시도 1회가 tb_api_log 1행이다.
 * <p>
 * 재시도 대상은 <b>HTTP 응답을 받은 일시 장애</b>(UNAVAILABLE 이고 status ≠ 0: 429·408·409·5xx·529)뿐이다. 뒤에 OpenAI 폴백이 있으므로
 * 오래 버티지 않는다 — 타임아웃·연결 실패·응답 해석 실패(status 0)는 재시도하지 않고, Retry-After 가 30초를 넘거나 실패한 시도가 30초 넘게 걸렸으면 바로 던진다.
 */
@Slf4j
public class ClaudeMessagesClient {

  /** 이보다 긴 Retry-After 는 기다리지 않고 바로 실패한다 — 폴백이 있으니 오래 기다릴 이유가 없다 */
  private static final long MAX_RETRY_AFTER_SECONDS = 30L;
  /** 실패한 시도 1회가 이보다 오래 걸렸으면(느린 5xx) 재시도하지 않는다 — 같은 지연이 반복되면 잡의 실행 창을 넘긴다 */
  private static final long MAX_RETRYABLE_ATTEMPT_MILLIS = 30_000L;
  private static final long BASE_BACKOFF_MILLIS = 500L;
  private static final long MAX_BACKOFF_MILLIS = 30_000L;
  private static final long JITTER_MILLIS = 250L;

  /** 재시도 대기 훅 — 테스트에서 실제 대기를 없앤다(OpenAiResponsesClient 와 같은 방식) */
  @FunctionalInterface
  public interface Sleeper {

    void sleep(long millis) throws InterruptedException;
  }

  protected final AnthropicClient client;
  protected final int maxRetries;
  protected final Sleeper sleeper;
  /** 시도 소요 시간 측정용 단조 시계(나노초) — 테스트에서 느린 응답을 흉내 낸다 */
  private final LongSupplier nanoClock;

  /**
   * 운영용 생성자 — 재시도 대기는 실제 {@code Thread.sleep}, 시도 소요는 {@code System.nanoTime} 으로 잰다.
   *
   * @param client     SDK 클라이언트(전송은 RestClientHttpClient, SDK 재시도 꺼짐)
   * @param maxRetries 일시 장애 재시도 횟수(음수는 0)
   */
  public ClaudeMessagesClient(AnthropicClient client, int maxRetries) {
    this(client, maxRetries, Thread::sleep);
  }

  /**
   * 재시도 대기를 바꿔 끼우는 생성자(테스트에서 실제 대기를 없앤다).
   */
  public ClaudeMessagesClient(AnthropicClient client, int maxRetries, Sleeper sleeper) {
    this(client, maxRetries, sleeper, System::nanoTime);
  }

  /**
   * 대기와 시계를 모두 주입받는 생성자(테스트에서 느린 응답을 흉내 낸다).
   */
  ClaudeMessagesClient(AnthropicClient client, int maxRetries, Sleeper sleeper, LongSupplier nanoClock) {
    this.client = client;
    this.maxRetries = Math.max(0, maxRetries);
    this.sleeper = sleeper;
    this.nanoClock = nanoClock;
  }

  /**
   * 메시지를 만든다. 일시 장애(UNAVAILABLE)만 {@code maxRetries} 회 재시도하고, 타임아웃·연결 실패와 소진·설정 오류·요청 오류는 재시도하지 않는다.
   *
   * @throws LlmProviderException 재시도를 끝낸 모든 실패
   */
  public Message create(MessageCreateParams params) {
    int attempts = maxRetries + 1;
    for (int attempt = 1; ; attempt++) {
      long startedAt = nanoClock.getAsLong();
      try {
        return requireMessageShape(client.messages().create(params));
      } catch (Exception e) {
        // SDK(Kotlin)는 checked 예외를 선언 없이 던질 수 있어 RuntimeException 이 아니라 Exception 으로 받는다
        LlmProviderException failure = ClaudeErrors.classify(e);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startedAt);
        if (!shouldRetry(failure, attempt, attempts, elapsedMillis)) {
          throw failure;
        }
        long waitMillis = backoffMillis(failure.getRetryAfterSeconds(), attempt);
        log.warn("Claude messages 시도 {}/{} 실패({}ms 뒤 재시도): {}", attempt, attempts, waitMillis, failure.getMessage());
        sleepBeforeRetry(waitMillis, failure);
      }
    }
  }

  /**
   * 2xx 응답이 Message 형태인지 필수 필드만 확인한다. SDK 는 응답 검증을 필드 접근 시점으로 미루므로(responseValidation 기본 off) 형태가 다른 JSON 도
   * 예외 없이 속 빈 {@link Message} 로 돌아오고, 호출부가 필드를 읽을 때에야 SDK 예외가 난다 — "실패는 전부 LlmProviderException" 계약이 깨진다.
   * 전체 검증({@code validate()})은 쓰지 않는다: SDK 가 모르는 stop_reason·블록 종류까지 거부해 API 가 값을 추가하면 멀쩡한 응답이 실패가 된다.
   */
  private static Message requireMessageShape(Message message) {
    message.id();
    message.content();
    message.model();
    message.usage().inputTokens();
    message.usage().outputTokens();
    return message;
  }

  /**
   * 이 실패를 다시 시도할지 정한다. 응답을 받은 일시 장애이고 시도 횟수가 남았더라도, 기다릴 시간이 길거나 방금 시도가 느렸으면 그만둔다.
   */
  private static boolean shouldRetry(LlmProviderException failure, int attempt, int attempts, long elapsedMillis) {
    boolean transientWithResponse = failure.getKind() == LlmFailureKind.UNAVAILABLE && failure.getStatus() != 0;
    if (!transientWithResponse || attempt >= attempts) {
      return false;
    }
    Long retryAfterSeconds = failure.getRetryAfterSeconds();
    if (retryAfterSeconds != null && retryAfterSeconds > MAX_RETRY_AFTER_SECONDS) {
      log.warn("Claude messages 시도 {}/{} 실패 — Retry-After {}초가 상한 {}초를 넘어 재시도하지 않는다", attempt, attempts, retryAfterSeconds, MAX_RETRY_AFTER_SECONDS);
      return false;
    }
    if (elapsedMillis > MAX_RETRYABLE_ATTEMPT_MILLIS) {
      log.warn("Claude messages 시도 {}/{} 실패 — 응답까지 {}ms 걸려(상한 {}ms) 재시도하지 않는다", attempt, attempts, elapsedMillis, MAX_RETRYABLE_ATTEMPT_MILLIS);
      return false;
    }
    return true;
  }

  /**
   * 재시도 전 대기 시간. Retry-After 가 있으면 그 값(호출부가 30초 이하임을 확인했다), 없으면 0.5s·2^(n-1) + 0~250ms 지터.
   */
  private static long backoffMillis(Long retryAfterSeconds, int attempt) {
    if (retryAfterSeconds != null) {
      return retryAfterSeconds * 1_000L;
    }
    // 시프트 폭을 묶어 maxRetries 가 크게 설정돼도 넘치지 않게 한다
    long base = Math.min(BASE_BACKOFF_MILLIS << Math.min(attempt - 1, 16), MAX_BACKOFF_MILLIS);
    return base + ThreadLocalRandom.current().nextLong(JITTER_MILLIS);
  }

  /**
   * 대기한다. 인터럽트되면 플래그를 복원하고 일시 장애로 던진다 — 직전 실패는 suppressed 로 남긴다.
   */
  private void sleepBeforeRetry(long millis, LlmProviderException lastFailure) {
    try {
      sleeper.sleep(millis);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      LlmProviderException interrupted = new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.UNAVAILABLE, 0, null,
          "재시도 대기 중 인터럽트", ie);
      interrupted.addSuppressed(lastFailure);
      throw interrupted;
    }
  }
}

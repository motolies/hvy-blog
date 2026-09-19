package kr.hvy.blog.modules.advisor.client.openai;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

/**
 * {@code POST /v1/responses} HTTP 클라이언트. 요청 1건 = api_log 1행이 되도록 재시도를 여기서 직접 한다
 * ({@code RestClientConfigurer} 가 전송 계층 자동 재시도를 꺼 두었고, OpenAI SDK 의 maxRetries 를 이 클래스가 대신한다).
 * <p>
 * 상태 분기는 {@code exchange} 로 직접 한다 — 헬퍼의 defaultStatusHandler 가 4xx/5xx 를 로그만 남기고 삼키므로 {@code retrieve()} 는 예외를 던지지 않는다.
 * 429·408·409·5xx·네트워크 오류는 Retry-After(초, 상한 30s) 또는 지수 백오프(0.5s·2^n + 250ms 지터)로 재시도하고, 그 외 4xx 는 즉시 실패한다.
 */
@Slf4j
public class OpenAiResponsesClient {

  public static final String RESPONSES_PATH = "/v1/responses";

  private static final long MAX_RETRY_WAIT_MILLIS = 30_000L;
  private static final long BASE_BACKOFF_MILLIS = 500L;
  private static final long JITTER_MILLIS = 250L;
  private static final int ERROR_BODY_ABBREVIATION = 300;

  /** 재시도 대기 훅 — 테스트에서 실제 sleep 을 없앤다 */
  @FunctionalInterface
  interface Sleeper {

    void sleep(long millis) throws InterruptedException;
  }

  private final RestClient restClient;
  private final int maxRetries;
  private final Sleeper sleeper;

  public OpenAiResponsesClient(RestClient restClient, int maxRetries) {
    this(restClient, maxRetries, Thread::sleep);
  }

  OpenAiResponsesClient(RestClient restClient, int maxRetries, Sleeper sleeper) {
    this.restClient = restClient;
    this.maxRetries = Math.max(0, maxRetries);
    this.sleeper = sleeper;
  }

  /**
   * 응답 1건을 만든다. 재시도 가능한 실패는 maxRetries 만큼 다시 시도하고, 소진되면 마지막 예외를 던진다.
   */
  public ResponsesResponse create(ResponsesRequest request) {
    String json = AdvisorJson.write(request);
    int attempts = maxRetries + 1;
    for (int attempt = 1; ; attempt++) {
      try {
        return post(json);
      } catch (OpenAiResponsesException e) {
        if (!e.isRetryable() || attempt >= attempts) {
          throw e;
        }
        log.warn("OpenAI responses 시도 {}/{} 실패(재시도): {}", attempt, attempts, e.getMessage());
        sleepBeforeRetry(e.getRetryAfterSeconds(), attempt);
      } catch (ResourceAccessException e) {
        // 연결 실패·타임아웃(RestClient 가 IOException 을 감싼다). api_log 에는 [no response] 로 이미 남았다
        OpenAiResponsesException wrapped = new OpenAiResponsesException(0, "io", "OpenAI 연결 실패: " + e.getMessage(), true, null, e);
        if (attempt >= attempts) {
          throw wrapped;
        }
        log.warn("OpenAI responses 시도 {}/{} 실패(네트워크, 재시도): {}", attempt, attempts, e.getMessage());
        sleepBeforeRetry(null, attempt);
      }
    }
  }

  /**
   * 1회 요청. 2xx 는 파싱, 나머지는 상태·오류 본문·Retry-After 를 담은 예외로 바꾼다.
   */
  private ResponsesResponse post(String json) {
    return restClient.post()
        .uri(RESPONSES_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(json)
        .exchange((req, res) -> {
          String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
          HttpStatusCode status = res.getStatusCode();
          if (status.is2xxSuccessful()) {
            return parse(status.value(), body);
          }
          ResponsesResponse.ApiError error = parseError(body);
          boolean retryable = status.value() == 408 || status.value() == 409 || status.value() == 429 || status.is5xxServerError();
          Long retryAfter = parseRetryAfter(res.getHeaders().getFirst("Retry-After"));
          String message = error != null && StringUtils.isNotBlank(error.message()) ? error.message() : StringUtils.abbreviate(body, ERROR_BODY_ABBREVIATION);
          throw new OpenAiResponsesException(status.value(), error == null ? null : error.code(), message, retryable, retryAfter);
        });
  }

  private ResponsesResponse parse(int status, String body) {
    try {
      return AdvisorJson.read(body, ResponsesResponse.class);
    } catch (JacksonException e) {
      throw new OpenAiResponsesException(status, "parse", "응답 파싱 실패: " + e.getMessage(), false, null, e);
    }
  }

  /**
   * 오류 본문 {@code {"error": {...}}} 을 읽는다. JSON 이 아니면 null.
   */
  private static ResponsesResponse.ApiError parseError(String body) {
    if (StringUtils.isBlank(body)) {
      return null;
    }
    try {
      return AdvisorJson.read(body, ResponsesResponse.class).error();
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * Retry-After(초). 숫자가 아니면 null → 기본 백오프.
   */
  private static Long parseRetryAfter(String header) {
    if (StringUtils.isBlank(header)) {
      return null;
    }
    try {
      return Math.max(0L, Long.parseLong(header.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 재시도 전 대기. Retry-After 가 있으면 그 값(상한 30s), 없으면 0.5s·2^(n-1) + 지터.
   */
  private void sleepBeforeRetry(Long retryAfterSeconds, int attempt) {
    long millis;
    if (retryAfterSeconds != null) {
      millis = Math.min(retryAfterSeconds * 1000L, MAX_RETRY_WAIT_MILLIS);
    } else {
      long base = Math.min(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)), MAX_RETRY_WAIT_MILLIS);
      millis = base + ThreadLocalRandom.current().nextLong(JITTER_MILLIS);
    }
    try {
      sleeper.sleep(millis);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new OpenAiResponsesException(0, "interrupted", "OpenAI 재시도 대기 중 인터럽트", false, null, ie);
    }
  }
}

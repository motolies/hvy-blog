package kr.hvy.blog.modules.advisor.client.openai;

import lombok.Getter;

/**
 * Responses API 호출 실패. 메시지는 {@code "<status>: <OpenAI error.message>"} 형태라 Slack 실패 한 줄("답변을 만들지 못했습니다: 400: …")에 원인이 그대로 보인다.
 * status 0 은 HTTP 응답이 없는 경우(네트워크·파싱).
 */
@Getter
public class OpenAiResponsesException extends RuntimeException {

  private final int status;
  private final String code;
  private final boolean retryable;
  private final Long retryAfterSeconds;

  public OpenAiResponsesException(int status, String code, String message, boolean retryable, Long retryAfterSeconds) {
    this(status, code, message, retryable, retryAfterSeconds, null);
  }

  public OpenAiResponsesException(int status, String code, String message, boolean retryable, Long retryAfterSeconds, Throwable cause) {
    super(status > 0 ? status + ": " + message : message, cause);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

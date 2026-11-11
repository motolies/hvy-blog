package kr.hvy.blog.modules.advisor.client.llm;

import lombok.Getter;

/**
 * 공급자 호출 실패(분류 포함). 1순위 공급자 클라이언트({@code ClaudeMessagesClient})가 재시도를 끝낸 뒤 던지고 {@link FallbackChatModel} 이 받아
 * {@link LlmFailureKind#isFallbackable()} 로 폴백 여부를 정한다. 메시지는 {@code "<공급자> <status>: <설명>"}.
 */
@Getter
public class LlmProviderException extends RuntimeException {

  private final LlmProvider provider;
  private final LlmFailureKind kind;
  /** HTTP 상태. 응답을 받지 못했으면(타임아웃·연결 실패·응답 해석 실패) 0 */
  private final int status;
  /** 서버가 준 Retry-After(초). 없으면 null */
  private final Long retryAfterSeconds;

  /**
   * @param provider          실패한 공급자
   * @param kind              실패 분류 — 폴백·차단 여부를 정한다
   * @param status            HTTP 상태(응답을 받지 못했으면 0)
   * @param retryAfterSeconds 서버가 준 Retry-After(초), 없으면 null
   * @param message           설명. 예외 메시지는 {@code "<공급자> <status>: <설명>"} 이 된다 — 키·토큰을 싣지 않는다
   * @param cause             원인 예외(없으면 null)
   */
  public LlmProviderException(LlmProvider provider, LlmFailureKind kind, int status, Long retryAfterSeconds, String message, Throwable cause) {
    super(provider + " " + status + ": " + message, cause);
    this.provider = provider;
    this.kind = kind;
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

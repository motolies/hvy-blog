package kr.hvy.blog.modules.advisor.client.openai.dto;

/**
 * Responses 경로의 추론·캐시 토큰 집계. Spring AI {@code Usage} 에는 자리가 없어 native usage 와 AssistantMessage 메타데이터로 나른다.
 */
public record ResponsesUsage(int reasoningTokens, int cachedTokens) {

  public static final ResponsesUsage ZERO = new ResponsesUsage(0, 0);

  /**
   * 두 집계를 더한다 (null 은 0 취급).
   */
  public ResponsesUsage plus(ResponsesUsage other) {
    return other == null ? this : new ResponsesUsage(reasoningTokens + other.reasoningTokens, cachedTokens + other.cachedTokens);
  }
}

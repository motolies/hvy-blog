package kr.hvy.blog.modules.advisor.client.llm;

/**
 * 공급자 중립 토큰 상세 — Spring AI {@code Usage} 의 표준 슬롯(prompt·completion·total)에 없는 추론·캐시 토큰(2026-11-11).
 * 공급자 모델이 {@code Usage.getNativeUsage()} 로 이 값을 싣고, 호출부({@code MarketJudgeClient}·{@code AdvisorChatClient})는 공급자 구현 클래스가 아니라
 * 이 record 로 읽는다. OpenAI·Claude 가 같은 모양의 record 를 따로 두던 것을 하나로 합쳤다.
 * <p>
 * 도구 루프가 여러 라운드면 Spring AI 의 usage 합산이 native usage 를 버린다(2라운드부터 null). 캐시 토큰은 표준 슬롯
 * {@code cacheReadInputTokens} 로도 실어 합산되게 하고, 추론 토큰의 라운드 누적은 공급자 모델의 몫이다 — OpenAI 모델은 AssistantMessage 메타데이터로
 * 턴 누적값을 나르고, Claude 모델은 누적하지 않는다(다중 라운드 채팅의 추론 토큰은 0).
 *
 * @param reasoningTokens 추론(thinking) 토큰. completion 토큰에 포함된 수다
 * @param cachedTokens    캐시에서 읽은 입력 토큰. prompt 토큰에 포함된 수다
 */
public record LlmTokenUsage(int reasoningTokens, int cachedTokens) {

  public static final LlmTokenUsage ZERO = new LlmTokenUsage(0, 0);

  /**
   * 두 집계를 더한다 (null 은 0 취급).
   */
  public LlmTokenUsage plus(LlmTokenUsage other) {
    return other == null ? this : new LlmTokenUsage(reasoningTokens + other.reasoningTokens, cachedTokens + other.cachedTokens);
  }
}

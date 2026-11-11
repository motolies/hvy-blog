package kr.hvy.blog.modules.advisor.client.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.UsageCalculator;

/**
 * 공급자 중립 응답 계약 — 종료 사유 상수와 메타데이터 키, 그것을 읽는 헬퍼. 호출부({@code MarketJudgeClient}·{@code AdvisorChatClient})와
 * {@link FallbackChatModel} 은 공급자 구현 클래스가 아니라 이 클래스만 본다.
 * <p>
 * <b>공급자 ChatModel 의 의무</b>(OpenAiResponsesChatModel·ClaudeMessagesChatModel):
 * <ol>
 *   <li>{@code ChatGenerationMetadata.finishReason} 은 {@code FINISH_*} 상수 중 하나</li>
 *   <li>{@link #PROVIDER_METADATA_KEY} 를 <b>ChatResponseMetadata 와 AssistantMessage 메타데이터 양쪽</b>에 {@link LlmProvider#name()} 으로 싣는다 —
 *       앞쪽은 호출부 기록용, 뒤쪽은 도구 루프 히스토리에서 "이 턴을 어느 공급자가 만들었나" 를 읽는 용도</li>
 *   <li>미완(INCOMPLETE) 사유는 {@link #INCOMPLETE_REASON_METADATA_KEY} 로 ChatResponseMetadata 에</li>
 * </ol>
 */
public final class LlmResponses {

  /** 메타데이터 키: 이 응답(·이 AssistantMessage)을 만든 공급자({@link LlmProvider#name()}) */
  public static final String PROVIDER_METADATA_KEY = "llm.provider";
  /** ChatResponseMetadata 키: 1순위 대신 폴백 공급자가 답한 사유(짧은 문자열). 폴백이 아니면 없다 */
  public static final String FALLBACK_REASON_METADATA_KEY = "llm.fallbackReason";
  /** ChatResponseMetadata 키: finishReason 이 INCOMPLETE 일 때의 공급자 원문 사유 */
  public static final String INCOMPLETE_REASON_METADATA_KEY = "llm.incompleteReason";

  /** finishReason: 모델이 도구를 불렀다(어드바이저가 실행 뒤 다음 라운드) */
  public static final String FINISH_TOOL_CALLS = "TOOL_CALLS";
  /** finishReason: 정상 완료 */
  public static final String FINISH_STOP = "STOP";
  /** finishReason: 출력 토큰 상한에서 잘림(있는 텍스트만 실림) */
  public static final String FINISH_LENGTH = "LENGTH";
  /** finishReason: 그 외 사유의 미완 — 사유는 {@link #INCOMPLETE_REASON_METADATA_KEY} */
  public static final String FINISH_INCOMPLETE = "INCOMPLETE";
  /** finishReason: 모델(·안전 분류기)이 응답을 거부 — 텍스트는 거부 문구일 수 있다 */
  public static final String FINISH_REFUSAL = "REFUSAL";

  private LlmResponses() {
  }

  /**
   * 응답의 finishReason(FINISH_* 상수). 결과·메타데이터가 없으면 null.
   */
  public static String finishReason(ChatResponse response) {
    if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
      return null;
    }
    return response.getResult().getMetadata().getFinishReason();
  }

  /**
   * 미완(INCOMPLETE) 사유. 미완이 아니거나 공급자가 싣지 않았으면 null.
   */
  public static String incompleteReason(ChatResponse response) {
    return metadataString(response, INCOMPLETE_REASON_METADATA_KEY);
  }

  /**
   * 이 응답을 만든 공급자. 공급자 모델이 만든 응답이 아니면(테스트 스텁 등) null.
   */
  public static LlmProvider provider(ChatResponse response) {
    if (response == null || response.getMetadata() == null) {
      return null;
    }
    return LlmProvider.from(response.getMetadata().get(PROVIDER_METADATA_KEY));
  }

  /**
   * 이 메시지를 만든 공급자. 메타데이터가 없는 메시지(Slack 히스토리·사용자 메시지)는 null.
   */
  public static LlmProvider provider(Message message) {
    if (message == null || message.getMetadata() == null) {
      return null;
    }
    return LlmProvider.from(message.getMetadata().get(PROVIDER_METADATA_KEY));
  }

  /**
   * 폴백 사유. 1순위가 답했으면 null.
   */
  public static String fallbackReason(ChatResponse response) {
    return metadataString(response, FALLBACK_REASON_METADATA_KEY);
  }

  /**
   * 응답 메타데이터에 키 하나를 더한 사본. ChatResponse 는 불변이라 generation(AssistantMessage 인스턴스 포함)은 그대로 두고 메타데이터만 다시 만든다 —
   * id·model·usage 와 기존 키는 보존된다.
   */
  public static ChatResponse withMetadata(ChatResponse response, String key, Object value) {
    return ChatResponse.builder().from(response).metadata(key, value).build();
  }

  /**
   * 응답의 usage 에, 같은 호출에서 먼저 과금된 다른 응답의 usage 를 더한 사본(1순위가 거부해 폴백이 답한 경우). 더할 usage 가 없으면 그대로 돌려준다.
   * generation(AssistantMessage 인스턴스)과 메타데이터의 다른 값은 보존된다 — 사본 조립은 Spring AI {@link UsageCalculator#withUsage} 에 맡긴다.
   * <p>
   * 합산은 직접 한다: {@link UsageCalculator#getCumulativeUsage} 는 native usage 를 버려서({@code null}) 추론 토큰({@link LlmTokenUsage})이 사라진다.
   * 여기서는 표준 슬롯(prompt·completion·total·cache)과 함께 native usage 의 추론·캐시 토큰도 더한다.
   *
   * @param response      호출부에 돌려줄 응답(폴백 공급자의 것)
   * @param billedEarlier 같은 호출에서 먼저 받았지만 버리는 응답(거부된 1순위 응답)
   */
  public static ChatResponse withAddedUsage(ChatResponse response, ChatResponse billedEarlier) {
    Usage earlier = usageOf(billedEarlier);
    if (response == null || response.getMetadata() == null || UsageCalculator.isEmpty(earlier)) {
      return response;
    }
    Usage current = response.getMetadata().getUsage();
    return UsageCalculator.withUsage(response, UsageCalculator.isEmpty(current) ? earlier : sum(current, earlier));
  }

  /**
   * 응답의 usage. 응답·메타데이터가 없으면 null.
   */
  private static Usage usageOf(ChatResponse response) {
    return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
  }

  /**
   * 두 usage 의 합. native usage 는 한쪽이라도 {@link LlmTokenUsage} 면 그 합이고, 아니면 앞쪽 것을 그대로 둔다.
   */
  private static Usage sum(Usage current, Usage earlier) {
    Object nativeUsage = current.getNativeUsage();
    if (nativeUsage instanceof LlmTokenUsage details) {
      nativeUsage = details.plus(earlier.getNativeUsage() instanceof LlmTokenUsage other ? other : null);
    } else if (nativeUsage == null && earlier.getNativeUsage() instanceof LlmTokenUsage other) {
      nativeUsage = other;
    }
    return new DefaultUsage(zeroIfNull(current.getPromptTokens()) + zeroIfNull(earlier.getPromptTokens()),
        zeroIfNull(current.getCompletionTokens()) + zeroIfNull(earlier.getCompletionTokens()),
        zeroIfNull(current.getTotalTokens()) + zeroIfNull(earlier.getTotalTokens()),
        nativeUsage,
        sumNullable(current.getCacheReadInputTokens(), earlier.getCacheReadInputTokens()),
        sumNullable(current.getCacheWriteInputTokens(), earlier.getCacheWriteInputTokens()));
  }

  /**
   * null 을 0 으로 읽는다.
   */
  private static int zeroIfNull(Integer tokens) {
    return tokens == null ? 0 : tokens;
  }

  /**
   * 캐시 토큰 합. 양쪽 다 보고하지 않았으면 null 을 유지한다(0 과 "모름" 을 구분).
   */
  private static Long sumNullable(Long a, Long b) {
    if (a == null && b == null) {
      return null;
    }
    return (a == null ? 0L : a) + (b == null ? 0L : b);
  }

  /**
   * 응답 메타데이터의 문자열 값. 키가 없거나 값이 null 이면 null.
   */
  private static String metadataString(ChatResponse response, String key) {
    if (response == null || response.getMetadata() == null || !response.getMetadata().containsKey(key)) {
      return null;
    }
    Object value = response.getMetadata().get(key);
    return value == null ? null : value.toString();
  }
}

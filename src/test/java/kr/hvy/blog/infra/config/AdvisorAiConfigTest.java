package kr.hvy.blog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 기본 옵션 헬퍼 — reasoningEffort 는 채팅 봇만, judge/assist(3-인자)는 미설정. ChatClient 의 defaultOptions 병합(combineWith)에서도 유지되는지.
 */
@DisplayName("AdvisorAiConfig.chatOptions - reasoningEffort 오버로드")
class AdvisorAiConfigTest {

  @Test
  @DisplayName("4-인자는 reasoningEffort 를 넣고, 3-인자와 공백은 넣지 않는다")
  void reasoningEffort_설정() {
    OpenAiChatOptions chat = AdvisorAiConfig.chatOptions("gpt-chat", 6000, null, "low").build();
    assertThat(chat.getModel()).isEqualTo("gpt-chat");
    assertThat(chat.getMaxCompletionTokens()).isEqualTo(6000);
    assertThat(chat.getReasoningEffort()).isEqualTo("low");
    assertThat(chat.getTemperature()).isNull();

    assertThat(AdvisorAiConfig.chatOptions("gpt-judge", 8000, null).build().getReasoningEffort()).isNull();
    assertThat(AdvisorAiConfig.chatOptions("gpt-chat", 6000, null, "  ").build().getReasoningEffort()).isNull();
    assertThat(AdvisorAiConfig.chatOptions("gpt-assist", 2000, 0.2, null).build().getTemperature()).isEqualTo(0.2);
  }

  @Test
  @DisplayName("모델 기본 옵션 위에 ChatClient defaultOptions 를 병합해도 model·reasoningEffort 가 살아남는다")
  void 병합유지() {
    OpenAiChatOptions modelDefault = AdvisorAiConfig.chatOptions("gpt-judge", 8000, null).build();

    OpenAiChatOptions merged = modelDefault.mutate().combineWith(AdvisorAiConfig.chatOptions("gpt-chat", 6000, null, "low")).build();

    assertThat(merged.getModel()).isEqualTo("gpt-chat");
    assertThat(merged.getMaxCompletionTokens()).isEqualTo(6000);
    assertThat(merged.getReasoningEffort()).isEqualTo("low");
    assertThat(modelDefault.mutate().combineWith(AdvisorAiConfig.chatOptions("gpt-assist", 2000, null)).build().getReasoningEffort()).isNull();
  }
}

package kr.hvy.blog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.llm.LlmChatOptions;
import kr.hvy.blog.modules.advisor.client.llm.LlmJsonSchema;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 역할별 모델 기본 옵션 헬퍼 — maxTokens 가 Responses max_output_tokens, reasoningEffort 는 채팅 봇(4-인자)만, temperature 는 non-null 일 때만.
 * 요청 customizer(MarketJudgeClient 의 공급자 중립 jsonSchema) 를 얹어도 모델 기본값이 살아남는지까지.
 */
@DisplayName("AdvisorAiConfig.chatOptions - 역할별 기본 옵션")
class AdvisorAiConfigTest {

  @Test
  @DisplayName("4-인자는 reasoningEffort 를 넣고, 3-인자와 공백은 넣지 않는다. temperature 는 null 이면 미설정")
  void 기본옵션() {
    ResponsesChatOptions chat = AdvisorAiConfig.chatOptions("gpt-chat", 6000, null, "low").build();
    assertThat(chat.getModel()).isEqualTo("gpt-chat");
    assertThat(chat.getMaxTokens()).isEqualTo(6000);
    assertThat(chat.getReasoningEffort()).isEqualTo("low");
    assertThat(chat.getTemperature()).isNull();
    assertThat(chat.getJsonSchema()).isNull();

    assertThat(AdvisorAiConfig.chatOptions("gpt-judge", 8000, null).build().getReasoningEffort()).isNull();
    assertThat(AdvisorAiConfig.chatOptions("gpt-chat", 6000, null, "  ").build().getReasoningEffort()).isNull();
    assertThat(AdvisorAiConfig.chatOptions("gpt-assist", 2000, 0.2, null).build().getTemperature()).isEqualTo(0.2);
  }

  @Test
  @DisplayName("모델 기본 옵션(judge) 위에 model 없는 요청 customizer(중립 jsonSchema) 를 병합해도 model·maxTokens 가 유지된다")
  void 요청customizer_병합() {
    ResponsesChatOptions judge = AdvisorAiConfig.chatOptions("gpt-judge", 8000, null).build();
    LlmJsonSchema schema = new LlmJsonSchema("AdviceResponse",
        AdvisorJson.MAPPER.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));

    // MarketJudgeClient 가 넘기는 형태 그대로 — 공급자 중립 빌더
    ResponsesChatOptions merged = judge.mutate().combineWith(LlmChatOptions.builder().jsonSchema(schema)).build();

    assertThat(merged.getModel()).isEqualTo("gpt-judge");
    assertThat(merged.getMaxTokens()).isEqualTo(8000);
    assertThat(merged.getJsonSchema()).isEqualTo(schema);
    assertThat(merged.getReasoningEffort()).isNull();
  }
}

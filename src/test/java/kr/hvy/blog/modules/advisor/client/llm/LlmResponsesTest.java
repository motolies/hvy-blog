package kr.hvy.blog.modules.advisor.client.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 공급자 중립 응답 계약 헬퍼. 공급자 모델이 메타데이터에 실은 값을 호출부·폴백 래퍼가 같은 방식으로 읽는지 고정한다.
 */
@DisplayName("LlmResponses - 종료 사유·공급자·폴백 사유 읽기")
class LlmResponsesTest {

  private static ChatResponse response(String finishReason, Map<String, Object> assistantMetadata, ChatResponseMetadata metadata) {
    AssistantMessage assistant = AssistantMessage.builder().content("답").properties(assistantMetadata).toolCalls(List.of()).media(List.of()).build();
    Generation generation = new Generation(assistant, ChatGenerationMetadata.builder().finishReason(finishReason).build());
    return new ChatResponse(List.of(generation), metadata);
  }

  @Test
  @DisplayName("공급자는 응답 메타데이터와 AssistantMessage 메타데이터 양쪽에서 상수명 문자열로 읽힌다")
  void provider_읽기() {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder().id("r1").model("m")
        .keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.CLAUDE.name()).build();
    ChatResponse response = response(LlmResponses.FINISH_STOP, Map.of(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.CLAUDE.name()), metadata);

    assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.CLAUDE);
    assertThat(LlmResponses.provider(response.getResult().getOutput())).isEqualTo(LlmProvider.CLAUDE);
    assertThat(LlmResponses.finishReason(response)).isEqualTo(LlmResponses.FINISH_STOP);
    assertThat(LlmResponses.fallbackReason(response)).isNull();
    assertThat(LlmResponses.incompleteReason(response)).isNull();
  }

  @Test
  @DisplayName("메타데이터가 없는 응답·메시지(테스트 스텁, Slack 히스토리)는 공급자 null 이다")
  void provider_없음() {
    ChatResponse stub = new ChatResponse(List.of(new Generation(new AssistantMessage("x"))));

    assertThat(LlmResponses.provider(stub)).isNull();
    assertThat(LlmResponses.provider(new AssistantMessage("히스토리"))).isNull();
    assertThat(LlmResponses.provider(new UserMessage("질문"))).isNull();
    assertThat(LlmResponses.provider((ChatResponse) null)).isNull();
    assertThat(LlmResponses.finishReason(null)).isNull();
    assertThat(LlmProvider.from("NOPE")).isNull();
    assertThat(LlmProvider.from(LlmProvider.OPENAI)).isEqualTo(LlmProvider.OPENAI);
  }

  @Test
  @DisplayName("withMetadata 는 id·model·usage·기존 키와 AssistantMessage 인스턴스를 보존하고 키 하나만 더한다")
  void withMetadata_보존() {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder().id("r1").model("gpt-x").usage(new DefaultUsage(10, 20, 30))
        .keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.OPENAI.name())
        .keyValue(LlmResponses.INCOMPLETE_REASON_METADATA_KEY, "content_filter").build();
    ChatResponse original = response(LlmResponses.FINISH_INCOMPLETE, Map.of(), metadata);

    ChatResponse marked = LlmResponses.withMetadata(original, LlmResponses.FALLBACK_REASON_METADATA_KEY, "EXHAUSTED");

    assertThat(LlmResponses.fallbackReason(marked)).isEqualTo("EXHAUSTED");
    assertThat(LlmResponses.provider(marked)).isEqualTo(LlmProvider.OPENAI);
    assertThat(LlmResponses.incompleteReason(marked)).isEqualTo("content_filter");
    assertThat(marked.getMetadata().getId()).isEqualTo("r1");
    assertThat(marked.getMetadata().getModel()).isEqualTo("gpt-x");
    assertThat(marked.getMetadata().getUsage().getTotalTokens()).isEqualTo(30);
    assertThat(marked.getResult().getOutput()).as("도구 루프 히스토리가 같은 인스턴스를 본다").isSameAs(original.getResult().getOutput());
    assertThat(LlmResponses.finishReason(marked)).isEqualTo(LlmResponses.FINISH_INCOMPLETE);
    assertThat(LlmResponses.fallbackReason(original)).as("원본은 바뀌지 않는다").isNull();
  }
}

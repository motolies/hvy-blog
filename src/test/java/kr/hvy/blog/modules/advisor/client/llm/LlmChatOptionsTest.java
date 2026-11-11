package kr.hvy.blog.modules.advisor.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 공급자 중립 옵션의 병합 계약. 폴백 래퍼의 기본 옵션이 이 타입이고 요청 옵션도 이 타입이라, mutate 가 필드를 다 나르고 combineWith 가 non-null 만
 * 덮어야 스키마·도구가 공급자 모델까지 살아서 도착한다(ResponsesChatOptionsTest 와 같은 불변식).
 */
@DisplayName("LlmChatOptions - mutate/combineWith 병합 계약")
class LlmChatOptionsTest {

  static final LlmJsonSchema SCHEMA = new LlmJsonSchema("Advice",
      AdvisorJson.MAPPER.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));

  static class Tools {

    @Tool(name = "echo", description = "echo")
    public String echo(String v) {
      return v;
    }
  }

  @Test
  @DisplayName("mutate().build() 는 base 10필드 + jsonSchema 를 전부 나르고 타입도 LlmChatOptions 다")
  void mutate_왕복() {
    ToolCallback[] callbacks = ToolCallbacks.from(new Tools());
    LlmChatOptions original = LlmChatOptions.builder()
        .model("m").frequencyPenalty(0.1).maxTokens(500).presencePenalty(0.2).stopSequences(List.of("END")).temperature(0.3).topK(5).topP(0.9)
        .toolCallbacks(List.of(callbacks)).toolContext(Map.of("k", "v"))
        .jsonSchema(SCHEMA)
        .build();

    LlmChatOptions copy = original.mutate().build();

    assertThat(copy).isExactlyInstanceOf(LlmChatOptions.class).isEqualTo(original);
    assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    assertThat(copy.getModel()).isEqualTo("m");
    assertThat(copy.getMaxTokens()).isEqualTo(500);
    assertThat(copy.getToolCallbacks()).hasSize(1);
    assertThat(copy.getToolContext()).containsEntry("k", "v");
    assertThat(copy.getJsonSchema()).isEqualTo(SCHEMA);
  }

  @Test
  @DisplayName("빈 builder 는 전부 null — 래퍼 기본 옵션으로 써도 공급자 기본값(model·maxTokens)을 덮지 않는다")
  void 기본값_null() {
    LlmChatOptions empty = LlmChatOptions.builder().build();
    assertThat(empty.getModel()).isNull();
    assertThat(empty.getMaxTokens()).isNull();
    assertThat(empty.getTemperature()).isNull();
    assertThat(empty.getJsonSchema()).isNull();
  }

  @Test
  @DisplayName("combineWith 는 other 의 non-null 만 덮는다 — jsonSchema 만 있는 customizer 를 얹어도 base 가 산다")
  void 병합_nonNull만() {
    LlmChatOptions base = LlmChatOptions.builder().model("base-model").maxTokens(8000).build();

    LlmChatOptions merged = base.mutate().combineWith(LlmChatOptions.builder().jsonSchema(SCHEMA)).build();

    assertThat(merged.getModel()).isEqualTo("base-model");
    assertThat(merged.getMaxTokens()).isEqualTo(8000);
    assertThat(merged.getJsonSchema()).isEqualTo(SCHEMA);

    LlmChatOptions kept = merged.mutate().combineWith(LlmChatOptions.builder().maxTokens(100)).build();
    assertThat(kept.getJsonSchema()).as("jsonSchema 가 없는 customizer 는 기존 스키마를 지우지 않는다").isEqualTo(SCHEMA);
    assertThat(kept.getMaxTokens()).isEqualTo(100);
  }

  @Test
  @DisplayName("진짜 ChatClient 경로: 래퍼 기본 옵션(전부 null) + .options(jsonSchema) + .tools + .toolContext 가 한 LlmChatOptions 로 모델에 도착한다")
  void ChatClient_경로_병합() {
    AtomicReference<Prompt> captured = new AtomicReference<>();
    ChatModel stub = new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        captured.set(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
      }

      @Override
      public ChatOptions getOptions() {
        return LlmChatOptions.builder().build();
      }
    };

    ChatClient.builder(stub).build().prompt().user("q")
        .options(LlmChatOptions.builder().jsonSchema(SCHEMA))
        .tools(new Tools())
        .toolContext(Map.of("userId", "U1"))
        .call().chatResponse();

    assertThat(captured.get().getOptions()).isExactlyInstanceOf(LlmChatOptions.class);
    LlmChatOptions sent = (LlmChatOptions) captured.get().getOptions();
    assertThat(sent.getJsonSchema()).isEqualTo(SCHEMA);
    assertThat(sent.getModel()).as("래퍼는 model 을 정하지 않는다 — 공급자 모델의 기본값 몫").isNull();
    assertThat(sent.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).containsExactly("echo");
    assertThat(sent.getToolContext()).containsEntry("userId", "U1");
  }

  @Test
  @DisplayName("ToolCallingChatOptions 다 — 이 타입이 아니면 ChatClient 가 .tools() 를 조용히 버린다")
  void 도구옵션_타입() {
    assertThat(LlmChatOptions.builder().build()).isInstanceOf(ToolCallingChatOptions.class);
  }

  @Test
  @DisplayName("스키마 이름 규칙(^[a-zA-Z0-9_-]{1,64}$)·객체 스키마를 생성 시점에 검증한다")
  void 스키마_검증() {
    assertThatThrownBy(() -> new LlmJsonSchema("bad name!", SCHEMA.schema())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmJsonSchema("a".repeat(65), SCHEMA.schema())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmJsonSchema("ok", AdvisorJson.MAPPER.readTree("[]"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmJsonSchema("ok", null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new LlmJsonSchema("Advice_v5-x", SCHEMA.schema()).name()).isEqualTo("Advice_v5-x");
  }
}

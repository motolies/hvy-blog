package kr.hvy.blog.modules.advisor.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesTextFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 옵션 병합 계약. Spring AI 2.0.1 ChatClient 는 "ChatModel 기본 옵션.mutate().combineWith(요청 customizer)" 로 요청 옵션을 만들므로,
 * mutate 가 필드를 다 나르고 combineWith 가 non-null 만 덮어야 역할별 모델의 model·maxTokens 가 요청까지 살아남는다(2026-09-19 assist→judge 오배선의 메커니즘).
 */
@DisplayName("ResponsesChatOptions - mutate/combineWith 병합 계약")
class ResponsesChatOptionsTest {

  static final ResponsesTextFormat FORMAT = ResponsesTextFormat.strictJsonSchema("Advice",
      AdvisorJson.MAPPER.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));

  static class Tools {

    @Tool(name = "echo", description = "echo")
    public String echo(String v) {
      return v;
    }
  }

  @Test
  @DisplayName("mutate().build() 는 base 10필드 + 추가 4필드를 전부 나른다")
  void mutate_왕복() {
    ToolCallback[] callbacks = ToolCallbacks.from(new Tools());
    ResponsesChatOptions original = ResponsesChatOptions.builder()
        .model("m").frequencyPenalty(0.1).maxTokens(500).presencePenalty(0.2).stopSequences(List.of("END")).temperature(0.3).topK(5).topP(0.9)
        .toolCallbacks(List.of(callbacks)).toolContext(Map.of("k", "v"))
        .reasoningEffort("low").toolChoice("auto").parallelToolCalls(Boolean.FALSE).textFormat(FORMAT)
        .build();

    ResponsesChatOptions copy = original.mutate().build();

    assertThat(copy).isEqualTo(original);
    assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    assertThat(copy.getModel()).isEqualTo("m");
    assertThat(copy.getMaxTokens()).isEqualTo(500);
    assertThat(copy.getToolCallbacks()).hasSize(1);
    assertThat(copy.getToolContext()).containsEntry("k", "v");
    assertThat(copy.getReasoningEffort()).isEqualTo("low");
    assertThat(copy.getToolChoice()).isEqualTo("auto");
    assertThat(copy.getParallelToolCalls()).isFalse();
    assertThat(copy.getTextFormat()).isEqualTo(FORMAT);
  }

  @Test
  @DisplayName("빈 builder 의 추가 필드는 전부 null — customizer 로 병합될 때 base 를 덮지 않는다")
  void 기본값_null() {
    ResponsesChatOptions empty = ResponsesChatOptions.builder().build();
    assertThat(empty.getModel()).isNull();
    assertThat(empty.getMaxTokens()).isNull();
    assertThat(empty.getReasoningEffort()).isNull();
    assertThat(empty.getToolChoice()).isNull();
    assertThat(empty.getParallelToolCalls()).isNull();
    assertThat(empty.getTextFormat()).isNull();
  }

  @Test
  @DisplayName("combineWith 는 other 의 non-null 만 덮는다 — textFormat 만 있는 customizer 를 얹어도 base 의 model·maxTokens 가 산다")
  void 병합_nonNull만() {
    ResponsesChatOptions base = ResponsesChatOptions.builder().model("gpt-judge").maxTokens(8000).reasoningEffort("high").build();

    ResponsesChatOptions merged = base.mutate().combineWith(ResponsesChatOptions.builder().textFormat(FORMAT)).build();

    assertThat(merged.getModel()).isEqualTo("gpt-judge");
    assertThat(merged.getMaxTokens()).isEqualTo(8000);
    assertThat(merged.getReasoningEffort()).isEqualTo("high");
    assertThat(merged.getTextFormat()).isEqualTo(FORMAT);

    ResponsesChatOptions overridden = base.mutate()
        .combineWith(ResponsesChatOptions.builder().model("gpt-chat").maxTokens(6000).reasoningEffort("low").toolChoice("none").parallelToolCalls(false))
        .build();
    assertThat(overridden.getModel()).isEqualTo("gpt-chat");
    assertThat(overridden.getMaxTokens()).isEqualTo(6000);
    assertThat(overridden.getReasoningEffort()).isEqualTo("low");
    assertThat(overridden.getToolChoice()).isEqualTo("none");
    assertThat(overridden.getParallelToolCalls()).isFalse();
    assertThat(overridden.getTextFormat()).isNull();
  }

  @Test
  @DisplayName("toolCallbacks 는 이어붙이고 toolContext 는 putAll 이다(Spring AI 기본 규칙 상속). 공통 ChatOptions 빌더와도 병합된다")
  void 병합_도구와_공통옵션() {
    ToolCallback[] callbacks = ToolCallbacks.from(new Tools());
    ResponsesChatOptions base = ResponsesChatOptions.builder().model("m").toolContext(Map.of("a", 1)).build();

    ResponsesChatOptions merged = base.mutate()
        .combineWith(ResponsesChatOptions.builder().toolCallbacks(List.of(callbacks)).toolContext(Map.of("b", 2)))
        .build();

    assertThat(merged.getToolCallbacks()).hasSize(1);
    assertThat(merged.getToolContext()).containsEntry("a", 1).containsEntry("b", 2);

    ResponsesChatOptions common = base.mutate().combineWith(ChatOptions.builder().temperature(0.2).build().mutate()).build();
    assertThat(common.getModel()).isEqualTo("m");
    assertThat(common.getTemperature()).isEqualTo(0.2);
  }

  @Test
  @DisplayName("진짜 ChatClient 경로: 모델 기본 옵션(judge)이 base 고 .options(textFormat) 는 그 위에 병합된다 — model 이 사라지지 않는다")
  void ChatClient_경로_병합() {
    ResponsesChatOptions modelDefaults = ResponsesChatOptions.builder().model("gpt-judge").maxTokens(8000).build();
    AtomicReference<Prompt> captured = new AtomicReference<>();
    ChatModel stub = new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        captured.set(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
      }

      @Override
      public ChatOptions getOptions() {
        return modelDefaults;
      }
    };

    ChatClient.builder(stub).build().prompt().user("q").options(ResponsesChatOptions.builder().textFormat(FORMAT)).call().chatResponse();

    assertThat(captured.get().getOptions()).isInstanceOf(ResponsesChatOptions.class);
    ResponsesChatOptions sent = (ResponsesChatOptions) captured.get().getOptions();
    assertThat(sent.getModel()).isEqualTo("gpt-judge");
    assertThat(sent.getMaxTokens()).isEqualTo(8000);
    assertThat(sent.getTextFormat()).isEqualTo(FORMAT);
  }

  @Test
  @DisplayName("text.format 이름 규칙(^[a-zA-Z0-9_-]{1,64}$)·객체 스키마를 생성 시점에 검증한다")
  void 텍스트포맷_검증() {
    assertThatThrownBy(() -> new ResponsesTextFormat("bad name!", FORMAT.schema(), true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResponsesTextFormat("a".repeat(65), FORMAT.schema(), true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResponsesTextFormat("ok", AdvisorJson.MAPPER.readTree("[]"), true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResponsesTextFormat("ok", null, true)).isInstanceOf(IllegalArgumentException.class);
    assertThat(ResponsesTextFormat.strictJsonSchema("Advice_v5-x", FORMAT.schema()).strict()).isTrue();
  }
}

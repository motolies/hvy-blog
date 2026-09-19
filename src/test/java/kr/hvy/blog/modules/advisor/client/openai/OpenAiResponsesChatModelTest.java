package kr.hvy.blog.modules.advisor.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.openai.dto.FunctionCallOutput;
import kr.hvy.blog.modules.advisor.client.openai.dto.FunctionTool;
import kr.hvy.blog.modules.advisor.client.openai.dto.InputMessage;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesTextFormat;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;

/**
 * Prompt ⇄ Responses API 변환 계약. HTTP 는 목이다(클라이언트 테스트가 따로 있다).
 */
@DisplayName("OpenAiResponsesChatModel - Prompt ⇄ Responses 변환")
class OpenAiResponsesChatModelTest {

  private static final String FUNCTION_CALL_BODY = """
      {"id":"resp_1","status":"completed","model":"gpt-test-2026",
       "output":[
         {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc-1","status":"completed"},
         {"type":"function_call","id":"fc_1","call_id":"call_1","name":"echo","arguments":"{\\"value\\":\\"a\\"}","status":"completed"}],
       "usage":{"input_tokens":100,"output_tokens":30,"total_tokens":130,
                "input_tokens_details":{"cached_tokens":8},"output_tokens_details":{"reasoning_tokens":20}}}
      """;
  private static final String MESSAGE_BODY = """
      {"id":"resp_2","status":"completed","model":"gpt-test-2026",
       "output":[
         {"type":"reasoning","id":"rs_2","summary":[],"encrypted_content":"enc-2","status":"completed"},
         {"type":"message","id":"msg_1","role":"assistant","status":"completed",
          "content":[{"type":"output_text","text":"첫 줄","annotations":[]},{"type":"output_text","text":"둘째 줄","annotations":[]}]}],
       "usage":{"input_tokens":150,"output_tokens":10,"total_tokens":160,
                "input_tokens_details":{"cached_tokens":50},"output_tokens_details":{"reasoning_tokens":5}}}
      """;
  private static final String REFUSAL_BODY = """
      {"id":"resp_3","status":"completed","model":"gpt-test-2026",
       "output":[{"type":"message","id":"msg_2","role":"assistant","status":"completed",
                  "content":[{"type":"refusal","refusal":"I'm sorry, I cannot assist with that request."}]}],
       "usage":{"input_tokens":81,"output_tokens":11,"total_tokens":92}}
      """;
  static final ResponsesTextFormat FORMAT = ResponsesTextFormat.strictJsonSchema("AdviceResponse",
      AdvisorJson.MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"picks\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"005930\"]}}},"
          + "\"required\":[\"picks\"],\"additionalProperties\":false}"));

  /** ToolContext 를 마지막 인자로 받는 도구 — 스키마 생성·이름 매핑 검증용 */
  static class FakeTools {

    @Tool(name = "echo", description = "값을 그대로 돌려준다")
    public Map<String, Object> echo(@ToolParam(description = "값") String value,
        @ToolParam(required = false, description = "반복 수") Integer times, ToolContext context) {
      return Map.of("value", value, "times", times == null ? 1 : times);
    }
  }

  private OpenAiResponsesClient client;
  private ResponsesChatOptions defaults;
  private OpenAiResponsesChatModel model;

  @BeforeEach
  void setUp() {
    client = mock(OpenAiResponsesClient.class);
    defaults = ResponsesChatOptions.builder().model("gpt-default").maxTokens(300).build();
    model = new OpenAiResponsesChatModel(client, defaults, 2);
  }

  private ResponsesRequest captureRequest() {
    ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(client).create(captor.capture());
    return captor.getValue();
  }

  private static ResponsesResponse body(String json) {
    return AdvisorJson.read(json, ResponsesResponse.class);
  }

  @Nested
  @DisplayName("Prompt → 요청")
  class ToRequest {

    @Test
    @DisplayName("첫 시스템 메시지는 instructions, 히스토리는 역할 메시지, 옵션은 model·max_output_tokens·reasoning.effort(소문자)·store=false. 도구가 없으면 tools·tool_choice·include·text 는 미전송")
    void 기본매핑() {
      when(client.create(any())).thenReturn(body(MESSAGE_BODY));
      ResponsesChatOptions options = ResponsesChatOptions.builder().model("gpt-chat").maxTokens(6000).reasoningEffort(" Low ").parallelToolCalls(false).build();
      List<Message> messages = List.of(new SystemMessage("시스템"), new UserMessage("이전 질문"), new AssistantMessage("이전 답"), new UserMessage("보여?"));

      model.call(new Prompt(messages, options));

      ResponsesRequest request = captureRequest();
      assertThat(request.model()).isEqualTo("gpt-chat");
      assertThat(request.instructions()).isEqualTo("시스템");
      assertThat(request.input()).containsExactly(
          new InputMessage("user", "이전 질문"), new InputMessage("assistant", "이전 답"), new InputMessage("user", "보여?"));
      assertThat(request.maxOutputTokens()).isEqualTo(6000);
      assertThat(request.reasoning().effort()).isEqualTo("low");
      assertThat(request.store()).isFalse();
      assertThat(request.include()).as("도구 없는 단발 호출은 reasoning 블롭 재전송이 없다").isNull();
      assertThat(request.tools()).isNull();
      assertThat(request.toolChoice()).isNull();
      assertThat(request.parallelToolCalls()).as("도구 없으면 옵션에 있어도 미전송").isNull();
      assertThat(request.text()).isNull();
      assertThat(request.temperature()).isNull();
    }

    @Test
    @DisplayName("reasoning effort 가 비면 reasoning 을 보내지 않고, temperature 는 있을 때만 보낸다")
    void 선택옵션() {
      when(client.create(any())).thenReturn(body(MESSAGE_BODY));
      ResponsesChatOptions options = ResponsesChatOptions.builder().model("gpt-chat").maxTokens(100).reasoningEffort("  ").temperature(0.3).build();

      model.call(new Prompt(List.of(new UserMessage("q")), options));

      ResponsesRequest request = captureRequest();
      assertThat(request.reasoning()).isNull();
      assertThat(request.temperature()).isEqualTo(0.3);
      assertThat(request.instructions()).isNull();
    }

    @Test
    @DisplayName("옵션이 없으면 모델 기본 옵션, 다른 타입이면 기본 옵션 위에 병합한다")
    void 옵션해석() {
      assertThat(model.resolveOptions(new Prompt(List.of(new UserMessage("q"))))).isSameAs(defaults);
      ResponsesChatOptions merged = model.resolveOptions(new Prompt(List.of(new UserMessage("q")), ChatOptions.builder().temperature(0.2).build()));
      assertThat(merged.getModel()).isEqualTo("gpt-default");
      assertThat(merged.getTemperature()).isEqualTo(0.2);
      assertThat(merged.getMaxTokens()).isEqualTo(300);
      ResponsesChatOptions own = ResponsesChatOptions.builder().model("x").build();
      assertThat(model.resolveOptions(new Prompt(List.of(new UserMessage("q")), own))).isSameAs(own);
    }

    @Test
    @DisplayName("구조화 출력 옵션은 text.format(json_schema·name·strict·schema) 로 나간다")
    void 텍스트포맷() {
      when(client.create(any())).thenReturn(body(MESSAGE_BODY));
      ResponsesChatOptions options = defaults.mutate().textFormat(FORMAT).build();

      model.call(new Prompt(List.of(new SystemMessage("s"), new UserMessage("{}")), options));

      ResponsesRequest request = captureRequest();
      assertThat(request.text().format().type()).isEqualTo("json_schema");
      assertThat(request.text().format().name()).isEqualTo("AdviceResponse");
      assertThat(request.text().format().strict()).isTrue();
      assertThat(request.text().format().schema()).isSameAs(FORMAT.schema());
      String json = AdvisorJson.MAPPER.writeValueAsString(request);
      assertThat(json).contains("\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"AdviceResponse\",\"strict\":true,\"schema\":{\"type\":\"object\"");
      assertThat(json).doesNotContain("\"include\"").doesNotContain("\"tools\"");
    }

    @Test
    @DisplayName("도구 정의는 이름·설명·스키마($schema 제거, additionalProperties=false, ToolContext 제외)이고 strict 가 아니다. 도구가 있으면 include·tool_choice·parallel_tool_calls 를 보낸다")
    void 도구정의() {
      when(client.create(any())).thenReturn(body(MESSAGE_BODY));
      ResponsesChatOptions options = defaults.mutate().toolCallbacks(List.of(ToolCallbacks.from(new FakeTools()))).toolChoice("auto").parallelToolCalls(false).build();

      model.call(new Prompt(List.of(new UserMessage("q")), options));

      ResponsesRequest request = captureRequest();
      assertThat(request.tools()).hasSize(1);
      assertThat(request.include()).containsExactly("reasoning.encrypted_content");
      assertThat(request.toolChoice()).isEqualTo("auto");
      assertThat(request.parallelToolCalls()).isFalse();
      FunctionTool tool = request.tools().get(0);
      assertThat(tool.type()).isEqualTo("function");
      assertThat(tool.name()).isEqualTo("echo");
      assertThat(tool.description()).isEqualTo("값을 그대로 돌려준다");
      assertThat(tool.strict()).isFalse();
      JsonNode parameters = tool.parameters();
      assertThat(parameters.path("$schema").isMissingNode()).isTrue();
      assertThat(parameters.path("type").asString()).isEqualTo("object");
      assertThat(parameters.path("additionalProperties").asString()).isEqualTo("false");
      assertThat(parameters.path("properties").path("value").path("type").asString()).isEqualTo("string");
      assertThat(parameters.path("properties").path("times").path("type").asString()).isEqualTo("integer");
      assertThat(parameters.path("properties").path("context").isMissingNode()).as("ToolContext 는 스키마에 없다").isTrue();
      assertThat(parameters.path("required").toString()).isEqualTo("[\"value\"]");
    }

    @Test
    @DisplayName("이전 라운드 AssistantMessage 는 원문 output 을 status 만 빼고 재전송하고, ToolResponse 는 function_call_output 이 된다")
    void 이전라운드_재전송() {
      when(client.create(any())).thenReturn(body(FUNCTION_CALL_BODY)).thenReturn(body(MESSAGE_BODY));
      ResponsesChatOptions options = defaults.mutate().toolCallbacks(List.of(ToolCallbacks.from(new FakeTools()))).build();
      List<Message> firstTurn = List.of(new SystemMessage("시스템"), new UserMessage("q"));
      ChatResponse first = model.call(new Prompt(firstTurn, options));
      AssistantMessage assistant = first.getResult().getOutput();
      ToolResponseMessage toolResponse = ToolResponseMessage.builder()
          .responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "echo", "{\"value\":\"a\"}")))
          .build();

      model.call(new Prompt(List.of(new SystemMessage("시스템"), new UserMessage("q"), assistant, toolResponse), options));

      ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
      verify(client, org.mockito.Mockito.times(2)).create(captor.capture());
      ResponsesRequest second = captor.getAllValues().get(1);
      assertThat(second.input()).hasSize(4);
      assertThat(second.input().get(0)).isEqualTo(new InputMessage("user", "q"));
      JsonNode reasoning = (JsonNode) second.input().get(1);
      assertThat(reasoning.path("type").asString()).isEqualTo("reasoning");
      assertThat(reasoning.path("encrypted_content").asString()).isEqualTo("enc-1");
      assertThat(reasoning.path("status").isMissingNode()).as("status 는 입력으로 되돌리지 않는다").isTrue();
      JsonNode functionCall = (JsonNode) second.input().get(2);
      assertThat(functionCall.path("type").asString()).isEqualTo("function_call");
      assertThat(functionCall.path("call_id").asString()).isEqualTo("call_1");
      assertThat(functionCall.path("status").isMissingNode()).isTrue();
      assertThat(second.input().get(3)).isEqualTo(FunctionCallOutput.of("call_1", "{\"value\":\"a\"}"));
      // 원본 메타데이터의 원문은 status 를 그대로 가진다(복사본만 제거)
      List<?> raw = (List<?>) assistant.getMetadata().get(OpenAiResponsesChatModel.OUTPUT_METADATA_KEY);
      assertThat(((JsonNode) raw.get(0)).path("status").asString()).isEqualTo("completed");
    }

    @Test
    @DisplayName("원문 없는 AssistantMessage 의 도구 호출은 function_call 항목을 합성한다")
    void 원문없는_도구호출_합성() {
      AssistantMessage assistant = AssistantMessage.builder()
          .content("")
          .toolCalls(List.of(new AssistantMessage.ToolCall("call_x", "function", "echo", "")))
          .build();

      List<Object> items = OpenAiResponsesChatModel.assistantItems(assistant);

      assertThat(items).containsExactly(Map.of("type", "function_call", "call_id", "call_x", "name", "echo", "arguments", "{}"));
    }

    @Test
    @DisplayName("도구 결과가 forceFinishAfterToolCalls 이상 쌓이면 tool_choice=none 으로 답을 강제한다")
    void 강제마무리() {
      when(client.create(any())).thenReturn(body(MESSAGE_BODY));
      ResponsesChatOptions options = defaults.mutate().toolCallbacks(List.of(ToolCallbacks.from(new FakeTools()))).build();
      ToolResponseMessage two = ToolResponseMessage.builder().responses(List.of(
          new ToolResponseMessage.ToolResponse("c1", "echo", "{}"), new ToolResponseMessage.ToolResponse("c2", "echo", "{}"))).build();

      model.call(new Prompt(List.of(new UserMessage("q"), two), options));

      ResponsesRequest request = captureRequest();
      assertThat(request.toolChoice()).isEqualTo("none");
      assertThat(request.tools()).as("도구 목록은 유지(재전송 항목의 참조 무결성)").hasSize(1);
    }
  }

  @Nested
  @DisplayName("응답 → ChatResponse")
  class ToChatResponse {

    @Test
    @DisplayName("function_call 은 toolCalls(id=call_id), 원문·라운드·누적 사용량은 메타데이터, usage 는 Spring Usage(+cacheRead)")
    void 도구호출응답() {
      when(client.create(any())).thenReturn(body(FUNCTION_CALL_BODY));

      ChatResponse response = model.call(new Prompt(List.of(new UserMessage("q")), defaults));

      AssistantMessage output = response.getResult().getOutput();
      assertThat(output.getText()).isEmpty();
      assertThat(output.hasToolCalls()).isTrue();
      assertThat(output.getToolCalls()).containsExactly(new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"value\":\"a\"}"));
      assertThat(response.hasToolCalls()).isTrue();
      assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(OpenAiResponsesChatModel.FINISH_TOOL_CALLS);
      assertThat((List<?>) output.getMetadata().get(OpenAiResponsesChatModel.OUTPUT_METADATA_KEY)).hasSize(2);
      assertThat(output.getMetadata().get(OpenAiResponsesChatModel.ROUND_USAGE_METADATA_KEY)).isEqualTo(new ResponsesUsage(20, 8));
      assertThat(output.getMetadata().get(OpenAiResponsesChatModel.CUMULATIVE_USAGE_METADATA_KEY)).isEqualTo(new ResponsesUsage(20, 8));
      assertThat(response.getMetadata().getId()).isEqualTo("resp_1");
      assertThat(response.getMetadata().getModel()).isEqualTo("gpt-test-2026");
      assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(100);
      assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(30);
      assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(130);
      assertThat(response.getMetadata().getUsage().getCacheReadInputTokens()).isEqualTo(8L);
      assertThat(response.getMetadata().getUsage().getNativeUsage()).isEqualTo(new ResponsesUsage(20, 8));
    }

    @Test
    @DisplayName("message 의 output_text 는 줄바꿈으로 잇고 finishReason 은 STOP, 누적 사용량은 이전 라운드를 더한다")
    void 텍스트응답_누적() {
      when(client.create(any())).thenReturn(body(MESSAGE_BODY));
      AssistantMessage previousRound = AssistantMessage.builder()
          .content("")
          .properties(Map.of(OpenAiResponsesChatModel.ROUND_USAGE_METADATA_KEY, new ResponsesUsage(20, 8)))
          .build();
      AssistantMessage slackHistory = new AssistantMessage("메타데이터 없는 히스토리");

      ChatResponse response = model.call(new Prompt(List.of(new UserMessage("q"), slackHistory, previousRound), defaults));

      assertThat(response.getResult().getOutput().getText()).isEqualTo("첫 줄\n둘째 줄");
      assertThat(response.hasToolCalls()).isFalse();
      assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(OpenAiResponsesChatModel.FINISH_STOP);
      assertThat(OpenAiResponsesChatModel.incompleteReason(response)).isNull();
      assertThat(OpenAiResponsesChatModel.cumulativeUsage(response)).contains(new ResponsesUsage(25, 58));
    }

    @Test
    @DisplayName("status=incomplete(max_output_tokens) 는 있는 텍스트를 쓰고 finishReason 은 LENGTH, 사유는 응답 메타데이터")
    void 미완응답_상한() {
      String incomplete = MESSAGE_BODY.replace("\"status\":\"completed\",\"model\"", "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"model\"");
      when(client.create(any())).thenReturn(body(incomplete));

      ChatResponse response = model.call(new Prompt(List.of(new UserMessage("q")), defaults));

      assertThat(response.getResult().getOutput().getText()).isEqualTo("첫 줄\n둘째 줄");
      assertThat(OpenAiResponsesChatModel.finishReason(response)).isEqualTo(OpenAiResponsesChatModel.FINISH_LENGTH);
      assertThat(OpenAiResponsesChatModel.incompleteReason(response)).isEqualTo("max_output_tokens");
    }

    @Test
    @DisplayName("status=incomplete 의 다른 사유(content_filter)는 LENGTH 가 아니라 INCOMPLETE 다 — 상한 안내가 잘못 나가지 않게")
    void 미완응답_기타사유() {
      String incomplete = MESSAGE_BODY.replace("\"status\":\"completed\",\"model\"", "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"model\"");
      when(client.create(any())).thenReturn(body(incomplete));

      ChatResponse response = model.call(new Prompt(List.of(new UserMessage("q")), defaults));

      assertThat(OpenAiResponsesChatModel.finishReason(response)).isEqualTo(OpenAiResponsesChatModel.FINISH_INCOMPLETE);
      assertThat(OpenAiResponsesChatModel.incompleteReason(response)).isEqualTo("content_filter");
    }

    @Test
    @DisplayName("output_text 없이 refusal 파트만 있으면 텍스트는 거부 문구, finishReason 은 REFUSAL")
    void 거부응답() {
      when(client.create(any())).thenReturn(body(REFUSAL_BODY));

      ChatResponse response = model.call(new Prompt(List.of(new UserMessage("q")), defaults.mutate().textFormat(FORMAT).build()));

      assertThat(response.getResult().getOutput().getText()).isEqualTo("I'm sorry, I cannot assist with that request.");
      assertThat(response.hasToolCalls()).isFalse();
      assertThat(OpenAiResponsesChatModel.finishReason(response)).isEqualTo(OpenAiResponsesChatModel.FINISH_REFUSAL);
      assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(81);
    }

    @Test
    @DisplayName("status=failed 는 예외 — 메시지에 OpenAI 오류 문구가 실린다")
    void 실패응답() {
      when(client.create(any())).thenReturn(body("{\"id\":\"resp_9\",\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"},\"output\":[]}"));

      assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("q")), defaults)))
          .isInstanceOf(OpenAiResponsesException.class)
          .hasMessageContaining("status=failed: boom");
    }

    @Test
    @DisplayName("Responses 모델이 만든 응답이 아니면 누적 사용량·finishReason 조회는 비어 있다")
    void 다른모델응답() {
      ChatResponse foreign = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("x"))));
      assertThat(OpenAiResponsesChatModel.cumulativeUsage(foreign)).isEmpty();
      assertThat(OpenAiResponsesChatModel.cumulativeUsage(null)).isEmpty();
      assertThat(OpenAiResponsesChatModel.incompleteReason(foreign)).isNull();
      assertThat(OpenAiResponsesChatModel.finishReason(null)).isNull();
    }
  }
}

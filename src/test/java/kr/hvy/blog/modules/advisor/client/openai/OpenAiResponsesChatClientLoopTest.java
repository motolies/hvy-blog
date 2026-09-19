package kr.hvy.blog.modules.advisor.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.chat.tool.ChatRequestScope;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.openai.dto.FunctionCallOutput;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;

/**
 * <b>툴체인 무변경의 증거</b> — 운영과 같은 조립(ChatClient 5-인자 builder + ToolCallingAdvisor + ToolCallingManager 상한 + {@code .tools(toolkit)} +
 * {@code .toolContext(scope)})으로 Responses 모델을 돌려, Spring AI 루프가 도구를 부르고 다음 라운드 입력을 만드는지 본다. HTTP 만 목이다.
 */
@DisplayName("OpenAiResponsesChatModel + ChatClient/ToolCallingAdvisor 도구 루프")
class OpenAiResponsesChatClientLoopTest {

  private static final String CALL_SNAPSHOT = """
      {"id":"resp_1","status":"completed","model":"gpt-test-2026",
       "output":[
         {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc-1","status":"completed"},
         {"type":"function_call","id":"fc_1","call_id":"call_1","name":"stockSnapshot","arguments":"{\\"code\\":\\"005930\\"}","status":"completed"}],
       "usage":{"input_tokens":100,"output_tokens":30,"total_tokens":130,
                "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":20}}}
      """;
  private static final String FINAL_ANSWER = """
      {"id":"resp_2","status":"completed","model":"gpt-test-2026",
       "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"삼성전자는 보합입니다.","annotations":[]}]}],
       "usage":{"input_tokens":150,"output_tokens":10,"total_tokens":160,
                "input_tokens_details":{"cached_tokens":50},"output_tokens_details":{"reasoning_tokens":5}}}
      """;

  /** 운영 툴킷과 같은 모양 — ToolContext 를 마지막 인자로 받고 ChatRequestScope 에 호출을 기록한다 */
  static class FakeToolkit {

    final List<String> invocations = new ArrayList<>();

    @Tool(name = "stockSnapshot", description = "종목 스냅샷")
    public Map<String, Object> stockSnapshot(@ToolParam(description = "종목코드") String code, ToolContext context) {
      ChatRequestScope.from(context).ifPresent(scope -> scope.enter("stockSnapshot"));
      invocations.add(code);
      return Map.of("code", code, "close", 71_000);
    }
  }

  private static ResponsesResponse body(String json) {
    return AdvisorJson.read(json, ResponsesResponse.class);
  }

  /**
   * AdvisorAiConfig.chatChatClient 와 같은 조립.
   */
  private static ChatClient chatClient(OpenAiResponsesChatModel model, int maxCallsPerTool, int maxTotalToolCalls) {
    ToolCallingManager manager = ToolCallingManager.builder()
        .maxCallsPerTool(maxCallsPerTool)
        .maxTotalToolCalls(maxTotalToolCalls)
        .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
        .build();
    return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
            ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true))
        .build();
  }

  @Test
  @DisplayName("function_call → @Tool 실행(ToolContext 전달) → 원문+결과 재전송 → 최종 답. usage 는 라운드 합산, 추론 토큰은 누적 메타데이터")
  void 도구루프_두라운드() {
    OpenAiResponsesClient client = mock(OpenAiResponsesClient.class);
    when(client.create(any())).thenReturn(body(CALL_SNAPSHOT)).thenReturn(body(FINAL_ANSWER));
    OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(client, ResponsesChatOptions.builder().model("gpt-test").maxTokens(500).reasoningEffort("low").build(), 12);
    FakeToolkit toolkit = new FakeToolkit();
    ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(60));

    ChatResponse response = chatClient(model, 6, 12).prompt()
        .system("시스템 프롬프트")
        .user("삼성전자 흐름?")
        .tools(toolkit)
        .toolContext(scope.toToolContext())
        .call()
        .chatResponse();

    assertThat(response.getResult().getOutput().getText()).isEqualTo("삼성전자는 보합입니다.");
    assertThat(toolkit.invocations).containsExactly("005930");
    assertThat(scope.calls()).as("ToolContext 로 ChatRequestScope 가 전달됐다").containsExactly("stockSnapshot");

    ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(client, times(2)).create(captor.capture());
    ResponsesRequest first = captor.getAllValues().get(0);
    assertThat(first.instructions()).isEqualTo("시스템 프롬프트");
    assertThat(first.tools()).extracting("name").containsExactly("stockSnapshot");
    assertThat(first.reasoning().effort()).isEqualTo("low");
    assertThat(first.maxOutputTokens()).isEqualTo(500);
    assertThat(first.toolChoice()).isNull();

    ResponsesRequest second = captor.getAllValues().get(1);
    assertThat(second.instructions()).isEqualTo("시스템 프롬프트");
    assertThat(second.input()).hasSize(4);
    assertThat(((JsonNode) second.input().get(1)).path("type").asString()).isEqualTo("reasoning");
    assertThat(((JsonNode) second.input().get(1)).path("encrypted_content").asString()).isEqualTo("enc-1");
    assertThat(((JsonNode) second.input().get(2)).path("call_id").asString()).isEqualTo("call_1");
    FunctionCallOutput output = (FunctionCallOutput) second.input().get(3);
    assertThat(output.callId()).isEqualTo("call_1");
    assertThat(output.output()).contains("\"code\"").contains("005930").contains("71000");

    Usage usage = response.getMetadata().getUsage();
    assertThat(usage.getPromptTokens()).isEqualTo(250);
    assertThat(usage.getCompletionTokens()).isEqualTo(40);
    assertThat(usage.getCacheReadInputTokens()).as("Spring AI 가 합산해 주는 캐시 토큰").isEqualTo(50L);
    assertThat(OpenAiResponsesChatModel.cumulativeUsage(response)).contains(new ResponsesUsage(25, 50));
  }

  @Test
  @DisplayName("도구별 상한 초과는 도구를 부르지 않고 Spring AI 오류 문구를 function_call_output 으로 넘긴다")
  void 도구별상한() {
    OpenAiResponsesClient client = mock(OpenAiResponsesClient.class);
    when(client.create(any())).thenReturn(body(CALL_SNAPSHOT)).thenReturn(body(CALL_SNAPSHOT.replace("call_1", "call_2")))
        .thenReturn(body(FINAL_ANSWER));
    OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(client, ResponsesChatOptions.builder().model("gpt-test").maxTokens(500).reasoningEffort("low").build(), 12);
    FakeToolkit toolkit = new FakeToolkit();
    ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(60));

    ChatResponse response = chatClient(model, 1, 12).prompt()
        .user("q")
        .tools(toolkit)
        .toolContext(scope.toToolContext())
        .call()
        .chatResponse();

    assertThat(response.getResult().getOutput().getText()).isEqualTo("삼성전자는 보합입니다.");
    assertThat(toolkit.invocations).as("두 번째 호출은 상한 초과라 실행되지 않는다").containsExactly("005930");
    ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(client, times(3)).create(captor.capture());
    ResponsesRequest third = captor.getAllValues().get(2);
    FunctionCallOutput breach = (FunctionCallOutput) third.input().get(third.input().size() - 1);
    assertThat(breach.callId()).isEqualTo("call_2");
    assertThat(breach.output()).contains("Tool call limit (1) exceeded for tool 'stockSnapshot'");
  }

  @Test
  @DisplayName("도구 결과가 max-total-tool-calls 만큼 쌓이면 다음 요청은 tool_choice=none 이다")
  void 총합상한_강제마무리() {
    OpenAiResponsesClient client = mock(OpenAiResponsesClient.class);
    when(client.create(any())).thenReturn(body(CALL_SNAPSHOT)).thenReturn(body(CALL_SNAPSHOT.replace("call_1", "call_2")))
        .thenReturn(body(FINAL_ANSWER));
    OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(client, ResponsesChatOptions.builder().model("gpt-test").maxTokens(500).reasoningEffort("low").build(), 2);
    FakeToolkit toolkit = new FakeToolkit();

    chatClient(model, 6, 2).prompt()
        .user("q")
        .tools(toolkit)
        .toolContext(Map.of("k", "v"))
        .call()
        .chatResponse();

    ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(client, times(3)).create(captor.capture());
    assertThat(captor.getAllValues().get(0).toolChoice()).isNull();
    assertThat(captor.getAllValues().get(1).toolChoice()).isNull();
    assertThat(captor.getAllValues().get(2).toolChoice()).isEqualTo("none");
  }
}

package kr.hvy.blog.modules.advisor.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.chat.tool.ChatRequestScope;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesChatModel;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesClient;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeModelSettings;
import kr.hvy.blog.modules.advisor.client.claude.RestClientHttpClient;
import kr.hvy.blog.modules.advisor.client.llm.FallbackChatModel;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderGate;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * <b>조립된 전체의 증거</b>(2026-11-11) — 두 공급자의 실제 스택을 운영과 같은 조립(AdvisorAiConfig.chatChatClient)으로 묶어 도구 루프를 돌린다.
 * Claude 는 Anthropic SDK → {@link RestClientHttpClient} → RestClient, OpenAI 는 {@link OpenAiResponsesClient} → RestClient 이고 HTTP 만
 * {@link MockRestServiceServer} 다. 트랙별 단위 테스트는 이웃을 목으로 두므로, 여기서만 볼 수 있는 것을 본다:
 * <ul>
 *   <li>Claude 2라운드 요청이 1라운드 응답(thinking 블록·signature 포함)을 한 글자도 바꾸지 않고 되돌려 보내는지 — 바뀌면 실제 API 가 400 을 낸다</li>
 *   <li>턴 도중 Claude 가 실패하면 OpenAI 가 Claude 의 도구 호출({@code toolu_…} id)과 그 결과를 이어받아 답하는지, 도구를 다시 실행하지 않는지</li>
 *   <li>차단 중에는 Claude 를 부르지 않고, 차단이 풀리면 다시 Claude 로 돌아오는지</li>
 * </ul>
 */
@DisplayName("Claude 1순위 → OpenAI 폴백: 실제 스택 + ChatClient 도구 루프")
class ProviderFallbackLoopTest {

  private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
  private static final String OPENAI_URL = "https://api.openai.com/v1/responses";
  private static final String SYSTEM_PROMPT = "시스템 프롬프트";

  private static final String CLAUDE_TOOL_USE = """
      {"id":"msg_r1","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[
         {"type":"thinking","thinking":"","signature":"EqQBCkYIBBgCKkD+sig-round-1=="},
         {"type":"text","text":"조회해 보겠습니다."},
         {"type":"tool_use","id":"toolu_01","name":"stockSnapshot","input":{"code":"005930"}}],
       "stop_reason":"tool_use","stop_sequence":null,
       "usage":{"input_tokens":100,"output_tokens":30,"cache_creation_input_tokens":40,"cache_read_input_tokens":0}}
      """;
  private static final String CLAUDE_FINAL = """
      {"id":"msg_r2","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[
         {"type":"thinking","thinking":"","signature":"sig-round-2"},
         {"type":"text","text":"삼성전자는 보합입니다(Claude)."}],
       "stop_reason":"end_turn","stop_sequence":null,
       "usage":{"input_tokens":20,"output_tokens":10,"cache_creation_input_tokens":0,"cache_read_input_tokens":140}}
      """;
  private static final String CLAUDE_OVERLOADED = """
      {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"},"request_id":"req_overloaded"}
      """;
  private static final String OPENAI_FINAL = """
      {"id":"resp_1","status":"completed","model":"gpt-test-2026",
       "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"삼성전자는 보합입니다(GPT).","annotations":[]}]}],
       "usage":{"input_tokens":150,"output_tokens":10,"total_tokens":160,
                "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":5}}}
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

  /** 테스트가 앞으로 돌리는 시계 — 게이트의 차단 만료를 실제 대기 없이 넘긴다 */
  static class MovableClock extends Clock {

    private Instant now = Instant.parse("2026-11-11T03:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private MockRestServiceServer claudeServer;
  private MockRestServiceServer openAiServer;
  private final List<String> claudeBodies = new ArrayList<>();
  private final List<String> openAiBodies = new ArrayList<>();
  private final List<String> alerts = new ArrayList<>();
  private final List<Boolean> alertErrors = new ArrayList<>();
  private final MovableClock clock = new MovableClock();
  private LlmProviderGate gate;
  /** 채팅 역할의 Claude 모델 ID — 요청의 model 이자 게이트의 모델 단위 차단 키 */
  private static final String CLAUDE_MODEL = "claude-test";
  private ChatClient chatClient;
  private FakeToolkit toolkit;

  @BeforeEach
  void setUp() {
    // 운영 RestClient(RestClientConfigurer)처럼 오류 상태를 삼키는 기본 status handler 를 둔다 — 두 클라이언트 모두 exchange 로 직접 분기해야 한다
    RestClient.Builder claudeBuilder = RestClient.builder().baseUrl("https://api.anthropic.com")
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
        });
    claudeServer = MockRestServiceServer.bindTo(claudeBuilder).build();
    ClaudeMessagesClient claudeClient = new ClaudeMessagesClient(RestClientHttpClient.anthropicClient(claudeBuilder.build()), 0);
    // AdvisorAiConfig.chatClaudeChatModel 과 같은 설정: 자동 캐싱 on, 강제 마무리 상한 = max-total-tool-calls
    ClaudeMessagesChatModel claude = new ClaudeMessagesChatModel(claudeClient, new ClaudeModelSettings(CLAUDE_MODEL, 4000, "medium", true, 12));

    RestClient.Builder openAiBuilder = RestClient.builder().baseUrl("https://api.openai.com")
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
        });
    openAiServer = MockRestServiceServer.bindTo(openAiBuilder).build();
    OpenAiResponsesChatModel openAi = new OpenAiResponsesChatModel(new OpenAiResponsesClient(openAiBuilder.build(), 0),
        ResponsesChatOptions.builder().model("gpt-test").maxTokens(500).build(), 12);

    gate = new LlmProviderGate(clock, Duration.ofMinutes(60), Duration.ofMinutes(5), Duration.ofMinutes(30), (text, error) -> {
      alerts.add(text);
      alertErrors.add(error);
      return true;
    });
    FallbackChatModel fallback = new FallbackChatModel(LlmProvider.CLAUDE, CLAUDE_MODEL, claude, () -> true, openAi, () -> true, gate);

    // AdvisorAiConfig.chatChatClient 와 같은 조립
    ToolCallingManager manager = ToolCallingManager.builder()
        .maxCallsPerTool(6)
        .maxTotalToolCalls(12)
        .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
        .build();
    chatClient = ChatClient.builder(fallback, ObservationRegistry.NOOP, null, null,
            ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true))
        .build();
    toolkit = new FakeToolkit();
  }

  /** 요청 본문을 모으는 매처 */
  private static RequestMatcher capture(List<String> bodies) {
    return request -> bodies.add(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
  }

  private void expectClaude(String responseBody) {
    claudeServer.expect(requestTo(CLAUDE_URL)).andExpect(method(HttpMethod.POST))
        .andExpect(header("anthropic-version", Matchers.not(Matchers.blankOrNullString())))
        .andExpect(headerDoesNotExist("x-api-key"))
        .andExpect(capture(claudeBodies))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
  }

  private void expectClaudeOverloaded() {
    claudeServer.expect(requestTo(CLAUDE_URL)).andExpect(capture(claudeBodies))
        .andRespond(withRawStatus(529).body(CLAUDE_OVERLOADED).contentType(MediaType.APPLICATION_JSON));
  }

  private void expectOpenAi(String responseBody) {
    openAiServer.expect(requestTo(OPENAI_URL)).andExpect(method(HttpMethod.POST)).andExpect(capture(openAiBodies))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
  }

  private ChatResponse ask(String question, ChatRequestScope scope) {
    return chatClient.prompt()
        .system(SYSTEM_PROMPT)
        .user(question)
        .tools(toolkit)
        .toolContext(scope.toToolContext())
        .call()
        .chatResponse();
  }

  private static JsonNode json(String body) {
    return AdvisorJson.MAPPER.readTree(body);
  }

  @Test
  @DisplayName("Claude 로 두 라운드: 2라운드 요청은 1라운드 응답 content 를 그대로 되돌려 보내고 system·tools·앞선 메시지가 1라운드와 같다. OpenAI 는 부르지 않는다")
  void claude_두라운드() {
    expectClaude(CLAUDE_TOOL_USE);
    expectClaude(CLAUDE_FINAL);
    ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(60));

    ChatResponse response = ask("삼성전자 흐름?", scope);

    assertThat(response.getResult().getOutput().getText()).isEqualTo("삼성전자는 보합입니다(Claude).");
    assertThat(toolkit.invocations).containsExactly("005930");
    assertThat(scope.calls()).as("ToolContext 로 ChatRequestScope 가 전달됐다").containsExactly("stockSnapshot");
    assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.CLAUDE);
    assertThat(LlmResponses.fallbackReason(response)).isNull();
    assertThat(response.getMetadata().getModel()).isEqualTo("claude-test-2026");
    assertThat(response.getMetadata().getUsage().getPromptTokens()).as("라운드 합산: (100+40+0) + (20+0+140)").isEqualTo(300);
    assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(40);
    assertThat(response.getMetadata().getUsage().getCacheReadInputTokens()).isEqualTo(140L);
    assertThat(gate.isBlocked(CLAUDE_MODEL)).isFalse();
    assertThat(alerts).isEmpty();

    claudeServer.verify();
    openAiServer.verify();
    assertThat(claudeBodies).hasSize(2);
    JsonNode first = json(claudeBodies.get(0));
    assertThat(first.path("model").asString()).isEqualTo("claude-test");
    assertThat(first.path("max_tokens").asInt()).isEqualTo(4000);
    assertThat(first.path("system").asString()).isEqualTo(SYSTEM_PROMPT);
    assertThat(first.path("tools")).hasSize(1);
    assertThat(first.path("tools").path(0).path("name").asString()).isEqualTo("stockSnapshot");
    assertThat(first.path("messages")).hasSize(1);
    assertThat(first.path("messages").path(0).path("role").asString()).isEqualTo("user");
    assertThat(first.path("output_config").path("effort").asString()).isEqualTo("medium");
    assertThat(first.path("cache_control").path("type").asString()).as("채팅은 자동 캐싱").isEqualTo("ephemeral");
    assertThat(first.has("temperature")).as("대상 모델은 temperature 를 400 으로 거부한다").isFalse();
    assertThat(first.has("thinking")).as("thinking 은 서버가 항상 켠다 — 보내면 400").isFalse();
    assertThat(first.has("tool_choice")).isFalse();

    JsonNode second = json(claudeBodies.get(1));
    assertThat(second.path("system")).as("thinking signature 는 system 에 묶인다").isEqualTo(first.path("system"));
    assertThat(second.path("tools")).as("…도구 집합에도").isEqualTo(first.path("tools"));
    assertThat(second.path("messages")).hasSize(3);
    assertThat(second.path("messages").path(0)).as("…앞선 메시지에도").isEqualTo(first.path("messages").path(0));
    JsonNode replayed = second.path("messages").path(1);
    assertThat(replayed.path("role").asString()).isEqualTo("assistant");
    assertThat(replayed.path("content")).as("1라운드 응답 content 를 그대로 — thinking 블록·signature·tool_use 까지")
        .isEqualTo(json(CLAUDE_TOOL_USE).path("content"));
    JsonNode toolResult = second.path("messages").path(2);
    assertThat(toolResult.path("role").asString()).isEqualTo("user");
    assertThat(toolResult.path("content").path(0).path("type").asString()).isEqualTo("tool_result");
    assertThat(toolResult.path("content").path(0).path("tool_use_id").asString()).isEqualTo("toolu_01");
    assertThat(toolResult.path("content").path(0).path("content").asString()).contains("71000");
    assertThat(second.has("temperature")).isFalse();
    assertThat(second.has("thinking")).isFalse();
  }

  @Test
  @DisplayName("턴 도중 Claude 가 529 로 실패하면 OpenAI 가 Claude 의 도구 호출(toolu_ id)과 결과를 이어받아 답한다. 도구는 다시 실행하지 않고 게이트가 차단된다")
  void 턴중간_폴백() {
    expectClaude(CLAUDE_TOOL_USE);
    expectClaudeOverloaded();
    expectOpenAi(OPENAI_FINAL);
    ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(60));

    ChatResponse response = ask("삼성전자 흐름?", scope);

    assertThat(response.getResult().getOutput().getText()).isEqualTo("삼성전자는 보합입니다(GPT).");
    assertThat(toolkit.invocations).as("Claude 가 부른 도구의 결과를 그대로 쓴다 — 다시 실행하지 않는다").containsExactly("005930");
    assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.OPENAI);
    assertThat(LlmResponses.fallbackReason(response)).isEqualTo(LlmFailureKind.UNAVAILABLE.name());
    assertThat(response.getMetadata().getModel()).isEqualTo("gpt-test-2026");
    claudeServer.verify();
    openAiServer.verify();

    assertThat(gate.currentBlock(CLAUDE_MODEL)).hasValueSatisfying(block -> {
      assertThat(block.kind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
      assertThat(block.status()).isEqualTo(529);
      assertThat(block.until()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
    });
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0)).contains("CLAUDE", "UNAVAILABLE", "529", "Overloaded");
    assertThat(alertErrors).as("일시 장애는 #hvy-notify(error=false)").containsExactly(false);

    JsonNode request = json(openAiBodies.get(0));
    assertThat(request.path("model").asString()).isEqualTo("gpt-test");
    assertThat(request.path("instructions").asString()).isEqualTo(SYSTEM_PROMPT);
    assertThat(request.path("tools").path(0).path("name").asString()).isEqualTo("stockSnapshot");
    JsonNode input = request.path("input");
    assertThat(input.path(0).path("role").asString()).isEqualTo("user");
    JsonNode functionCall = find(input, "function_call");
    assertThat(functionCall.path("call_id").asString()).as("Claude 의 tool_use id 를 call_id 로 이어받는다").isEqualTo("toolu_01");
    assertThat(functionCall.path("name").asString()).isEqualTo("stockSnapshot");
    assertThat(json(functionCall.path("arguments").asString()).path("code").asString()).isEqualTo("005930");
    JsonNode functionOutput = find(input, "function_call_output");
    assertThat(functionOutput.path("call_id").asString()).isEqualTo("toolu_01");
    assertThat(functionOutput.path("output").asString()).contains("71000");
    assertThat(openAiBodies.get(0)).as("Claude 의 thinking 블록·signature 는 OpenAI 로 넘어가지 않는다").doesNotContain("sig-round-1");
  }

  @Test
  @DisplayName("차단 중인 새 턴은 Claude 를 부르지 않고 OpenAI 로 가고(BLOCKED), 차단 시간이 지나면 Claude 로 돌아와 성공 시 차단이 풀린다")
  void 차단과_복귀() {
    // MockRestServiceServer 는 요청이 시작된 뒤 기대를 더할 수 없어 세 질문의 기대를 먼저 다 건다:
    // Claude 는 실패 1건 + 복귀 1건(차단 중에는 0건), OpenAI 는 폴백 2건
    expectClaudeOverloaded();
    expectClaude(CLAUDE_FINAL);
    expectOpenAi(OPENAI_FINAL);
    expectOpenAi(OPENAI_FINAL);

    ChatResponse failedOver = ask("첫 질문", new ChatRequestScope(Instant.now().plusSeconds(60)));
    assertThat(LlmResponses.fallbackReason(failedOver)).isEqualTo(LlmFailureKind.UNAVAILABLE.name());
    assertThat(claudeBodies).hasSize(1);
    assertThat(openAiBodies).hasSize(1);

    // 차단 중(4분 경과): Claude 를 부르지 않는다
    clock.advance(Duration.ofMinutes(4));
    ChatResponse blocked = ask("둘째 질문", new ChatRequestScope(Instant.now().plusSeconds(60)));
    assertThat(LlmResponses.provider(blocked)).isEqualTo(LlmProvider.OPENAI);
    assertThat(LlmResponses.fallbackReason(blocked)).isEqualTo(FallbackChatModel.blockedReason(LlmFailureKind.UNAVAILABLE));
    assertThat(claudeBodies).as("차단 중에는 Claude 요청이 늘지 않는다").hasSize(1);
    assertThat(openAiBodies).hasSize(2);
    assertThat(alerts).as("같은 날 같은 분류는 알림 1통").hasSize(1);

    // 차단 5분이 지나면(6분 경과) 다음 호출이 Claude 를 다시 시도한다
    clock.advance(Duration.ofMinutes(2));
    ChatResponse recovered = ask("셋째 질문", new ChatRequestScope(Instant.now().plusSeconds(60)));
    assertThat(claudeBodies).hasSize(2);
    assertThat(LlmResponses.provider(recovered)).isEqualTo(LlmProvider.CLAUDE);
    assertThat(LlmResponses.fallbackReason(recovered)).isNull();
    assertThat(recovered.getResult().getOutput().getText()).isEqualTo("삼성전자는 보합입니다(Claude).");
    assertThat(gate.isBlocked(CLAUDE_MODEL)).isFalse();
    assertThat(openAiBodies).hasSize(2);
    claudeServer.verify();
    openAiServer.verify();
  }

  /** input 배열에서 type 이 일치하는 첫 항목 */
  private static JsonNode find(JsonNode input, String type) {
    for (JsonNode item : input) {
      if (type.equals(item.path("type").asString())) {
        return item;
      }
    }
    throw new AssertionError("input 에 type=" + type + " 항목이 없다: " + input);
  }
}

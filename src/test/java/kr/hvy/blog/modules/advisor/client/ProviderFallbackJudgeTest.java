package kr.hvy.blog.modules.advisor.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import kr.hvy.blog.modules.advisor.application.service.AdviceSchemaFactory;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.application.service.LessonSchemaFactory;
import kr.hvy.blog.modules.advisor.application.service.LongTermNarrativeSchemaFactory;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.application.service.MorningAdviceSchemaFactory;
import kr.hvy.blog.modules.advisor.application.service.NoteSchemaFactory;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesChatModel;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesClient;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeModelSettings;
import kr.hvy.blog.modules.advisor.client.claude.RestClientHttpClient;
import kr.hvy.blog.modules.advisor.client.llm.FallbackChatModel;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderGate;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 판단(구조화 출력, 단발 호출) 경로의 <b>조립된 전체</b>(2026-11-11) — 운영과 같은 조립(AdvisorAiConfig: MarketJudgeClient → ChatClient → FallbackChatModel →
 * Claude/OpenAI 실제 스택)에서 HTTP 만 {@link MockRestServiceServer} 다. 이 기능의 본래 요구인 "Claude 가 소진되면 GPT 로" 를 여기서 끝에서 끝까지 본다:
 * <ul>
 *   <li>Claude 가 답할 때 요청에 스키마·effort 가 실리고 결과에 공급자가 기록되는지 — 실제 스키마 5종이 Jackson 3 → SDK(Jackson 2) 경계를 손실 없이 넘는지</li>
 *   <li>소진(402 · 지출 한도 400 · 티어 상한 429)이면 같은 스키마로 OpenAI 가 답하고, 이후 호출은 Claude 를 부르지 않는지</li>
 *   <li>폴백하지 않기로 한 것(요청 오류 400 · 출력 절단)은 정말 OpenAI 로 넘어가지 않는지</li>
 * </ul>
 */
@DisplayName("Claude 1순위 → OpenAI 폴백: 실제 스택 + 판단(구조화 출력)")
class ProviderFallbackJudgeTest {

  private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
  private static final String OPENAI_URL = "https://api.openai.com/v1/responses";
  /** 판단 역할의 Claude 모델 ID — 요청의 model 이자 게이트의 모델 단위 차단 키 */
  private static final String CLAUDE_MODEL = "claude-judge";
  private static final String OK_SCHEMA = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}";

  private static final String CLAUDE_OK = """
      {"id":"msg_ok","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[
         {"type":"thinking","thinking":"","signature":"sig-judge"},
         {"type":"text","text":"{\\"ok\\":true}"}],
       "stop_reason":"end_turn","stop_sequence":null,
       "usage":{"input_tokens":900,"output_tokens":120,"cache_creation_input_tokens":0,"cache_read_input_tokens":100,
                "output_tokens_details":{"thinking_tokens":80}}}
      """;
  private static final String CLAUDE_REFUSAL = """
      {"id":"msg_refusal","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[],
       "stop_reason":"refusal","stop_sequence":null,
       "stop_details":{"type":"refusal","category":"cyber","explanation":"요청을 처리할 수 없습니다."},
       "usage":{"input_tokens":81,"output_tokens":0}}
      """;
  private static final String CLAUDE_TRUNCATED = """
      {"id":"msg_cut","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[{"type":"text","text":"{\\"ok\\":"}],
       "stop_reason":"max_tokens","stop_sequence":null,
       "usage":{"input_tokens":900,"output_tokens":16000}}
      """;
  private static final String OPENAI_OK = """
      {"id":"resp_ok","status":"completed","model":"gpt-test-2026",
       "output":[{"type":"message","id":"msg","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"{\\"ok\\":true}","annotations":[]}]}],
       "usage":{"input_tokens":800,"output_tokens":60,"total_tokens":860,
                "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":40}}}
      """;

  private MockRestServiceServer claudeServer;
  private MockRestServiceServer openAiServer;
  private final List<String> claudeBodies = new ArrayList<>();
  private final List<String> openAiBodies = new ArrayList<>();
  private final List<String> alerts = new ArrayList<>();
  private final List<Boolean> alertErrors = new ArrayList<>();
  private final Instant now = Instant.parse("2026-11-11T10:30:00Z");
  private final AtomicBoolean claudeReady = new AtomicBoolean(true);
  private LlmProviderGate gate;
  private MarketJudgeClient judge;

  @BeforeEach
  void setUp() {
    RestClient.Builder claudeBuilder = RestClient.builder().baseUrl("https://api.anthropic.com")
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
        });
    claudeServer = MockRestServiceServer.bindTo(claudeBuilder).build();
    // AdvisorAiConfig.judgeClaudeChatModel 과 같은 설정: 도구 없음 → 자동 캐싱·강제 마무리 끔. 재시도 0 으로 대기 없이 분류만 본다
    ClaudeMessagesChatModel claude = new ClaudeMessagesChatModel(
        new ClaudeMessagesClient(RestClientHttpClient.anthropicClient(claudeBuilder.build()), 0),
        new ClaudeModelSettings(CLAUDE_MODEL, 16000, "high", false, 0));

    RestClient.Builder openAiBuilder = RestClient.builder().baseUrl("https://api.openai.com")
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
        });
    openAiServer = MockRestServiceServer.bindTo(openAiBuilder).build();
    OpenAiResponsesChatModel openAi = new OpenAiResponsesChatModel(new OpenAiResponsesClient(openAiBuilder.build(), 0),
        ResponsesChatOptions.builder().model("gpt-judge").maxTokens(777).build(), 0);

    gate = new LlmProviderGate(Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(60), Duration.ofMinutes(5), Duration.ofMinutes(30),
        (text, error) -> {
          alerts.add(text);
          alertErrors.add(error);
          return true;
        });
    FallbackChatModel fallback = new FallbackChatModel(LlmProvider.CLAUDE, CLAUDE_MODEL, claude, claudeReady::get, openAi, () -> true, gate);
    judge = new MarketJudgeClient(ChatClient.builder(fallback).build(), "hint-model");
  }

  private static RequestMatcher capture(List<String> bodies) {
    return request -> bodies.add(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8));
  }

  private void expectClaude(String responseBody) {
    claudeServer.expect(requestTo(CLAUDE_URL)).andExpect(capture(claudeBodies)).andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
  }

  private void expectClaudeError(int status, String responseBody) {
    claudeServer.expect(requestTo(CLAUDE_URL)).andExpect(capture(claudeBodies))
        .andRespond(withRawStatus(status).body(responseBody).contentType(MediaType.APPLICATION_JSON));
  }

  private void expectOpenAi(String responseBody) {
    openAiServer.expect(requestTo(OPENAI_URL)).andExpect(capture(openAiBodies)).andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
  }

  private static JsonNode json(String body) {
    return AdvisorJson.MAPPER.readTree(body);
  }

  private static String error(String type, String message, String detailsJson) {
    return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\""
        + (detailsJson == null ? "" : ",\"details\":" + detailsJson) + "},\"request_id\":\"req_x\"}";
  }

  /** 운영 스키마 팩토리 5종 + 최소 스키마. LessonSchemaFactory 는 타입 배열(["string","null"])과 null enum 을 쓴다 */
  static Stream<Arguments> schemas() {
    List<String> tickers = List.of("005930", "000660");
    List<String> sectors = List.of("G25", "G45");
    return Stream.of(
        Arguments.of("최소", OK_SCHEMA),
        Arguments.of("AdviceSchemaFactory", AdviceSchemaFactory.schemaJson(tickers, sectors, List.of("N1", "N2"))),
        Arguments.of("MorningAdviceSchemaFactory", MorningAdviceSchemaFactory.schemaJson(tickers, List.of("035420"), List.of("005930", "000660", "035420"))),
        Arguments.of("LongTermNarrativeSchemaFactory", LongTermNarrativeSchemaFactory.schemaJson(tickers)),
        Arguments.of("NoteSchemaFactory", NoteSchemaFactory.schemaJson(tickers, sectors)),
        Arguments.of("LessonSchemaFactory", LessonSchemaFactory.schemaJson(sectors)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schemas")
  @DisplayName("Claude 가 답한다: 요청에 스키마(원문 그대로)·effort 가 실리고 temperature·thinking 은 없다. 결과에 공급자와 추론·캐시 토큰이 기록된다")
  void claude_구조화출력(String name, String schemaJson) {
    expectClaude(CLAUDE_OK);

    MarketJudgeClient.CallResult<Map> result = judge.call("시스템 프롬프트", "{\"q\":1}", schemaJson, Map.class);

    assertThat(result.value()).containsEntry("ok", true);
    assertThat(result.provider()).isEqualTo(LlmProvider.CLAUDE);
    assertThat(result.model()).isEqualTo("claude-test-2026");
    assertThat(result.responseId()).isEqualTo("msg_ok");
    assertThat(result.usage().getPromptTokens()).as("input + cache_creation + cache_read").isEqualTo(1000);
    assertThat(result.usage().getCompletionTokens()).isEqualTo(120);
    assertThat(result.reasoningTokens()).as("thinking 토큰").isEqualTo(80);
    assertThat(result.cachedTokens()).isEqualTo(100);
    assertThat(result.options()).containsEntry("provider", "CLAUDE").doesNotContainKey("fallbackReason");
    claudeServer.verify();
    openAiServer.verify();
    assertThat(gate.isBlocked(CLAUDE_MODEL)).isFalse();

    JsonNode request = json(claudeBodies.get(0));
    assertThat(request.path("model").asString()).isEqualTo("claude-judge");
    assertThat(request.path("max_tokens").asInt()).isEqualTo(16000);
    assertThat(request.path("system").asString()).isEqualTo("시스템 프롬프트");
    assertThat(request.path("messages")).hasSize(1);
    assertThat(request.path("messages").path(0).path("role").asString()).isEqualTo("user");
    assertThat(request.path("messages").path(0).path("content").path(0).path("text").asString()).isEqualTo("{\"q\":1}");
    assertThat(request.path("output_config").path("effort").asString()).isEqualTo("high");
    assertThat(request.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
    assertThat(request.path("output_config").path("format").path("schema")).as("스키마가 Jackson 3 → SDK(Jackson 2) 경계를 손실 없이 넘는다")
        .isEqualTo(json(schemaJson));
    assertThat(request.has("temperature")).isFalse();
    assertThat(request.has("thinking")).isFalse();
    assertThat(request.has("tools")).isFalse();
    assertThat(request.has("cache_control")).as("단발 호출은 자동 캐싱을 켜지 않는다").isFalse();
  }

  /** 소진의 모양 — 결제 문제(402), 직접 설정한 지출 한도(400), 티어 월 상한(429 + error_code), 다른 상태에 실린 billing_error 유형 */
  static Stream<Arguments> exhausted() {
    return Stream.of(
        Arguments.of("402 billing_error", 402, error("billing_error", "Your credit balance is too low.", null)),
        Arguments.of("400 지출 한도", 400, error("invalid_request_error",
            "You have reached your specified API usage limits. You will regain access on 2026-12-01 at 00:00 UTC.", null)),
        Arguments.of("429 티어 월 상한", 429, error("rate_limit_error", "You have reached your API usage limits.",
            "{\"error_code\":\"enforced_spend_limit_reached\"}")),
        Arguments.of("403 billing_error 유형", 403, error("billing_error", "Your account has a billing problem.", null)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("exhausted")
  @DisplayName("Claude 소진 → 같은 스키마로 OpenAI 가 답한다(OpenAI 의 모델·출력 상한 유지). 60분 차단되고 다음 호출은 Claude 를 부르지 않는다")
  void 소진_폴백(String name, int status, String errorBody) {
    expectClaudeError(status, errorBody);
    expectOpenAi(OPENAI_OK);
    expectOpenAi(OPENAI_OK);

    MarketJudgeClient.CallResult<Map> result = judge.call("시스템 프롬프트", "{\"q\":1}", OK_SCHEMA, Map.class);

    assertThat(result.value()).containsEntry("ok", true);
    assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(result.model()).isEqualTo("gpt-test-2026");
    assertThat(result.reasoningTokens()).isEqualTo(40);
    assertThat(result.options()).containsEntry("provider", "OPENAI").containsEntry("fallbackReason", LlmFailureKind.EXHAUSTED.name());
    assertThat(claudeBodies).as("소진은 재시도하지 않는다").hasSize(1);

    JsonNode request = json(openAiBodies.get(0));
    assertThat(request.path("model").asString()).as("Claude 의 모델 ID 가 OpenAI 요청으로 새지 않는다").isEqualTo("gpt-judge");
    assertThat(request.path("max_output_tokens").asInt()).as("…출력 상한도 OpenAI 것").isEqualTo(777);
    assertThat(request.path("instructions").asString()).isEqualTo("시스템 프롬프트");
    assertThat(request.path("text").path("format").path("type").asString()).isEqualTo("json_schema");
    assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
    assertThat(request.path("text").path("format").path("name").asString()).isEqualTo("Map");
    assertThat(request.path("text").path("format").path("schema")).isEqualTo(json(OK_SCHEMA));

    assertThat(gate.currentBlock(CLAUDE_MODEL)).hasValueSatisfying(block -> {
      assertThat(block.kind()).isEqualTo(LlmFailureKind.EXHAUSTED);
      assertThat(block.status()).isEqualTo(status);
      assertThat(block.until()).isEqualTo(now.plus(Duration.ofMinutes(60)));
    });
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0)).contains("CLAUDE", "EXHAUSTED");
    assertThat(alertErrors).as("소진은 #hvy-notify(error=false)").containsExactly(false);

    MarketJudgeClient.CallResult<Map> next = judge.call("시스템 프롬프트", "{\"q\":2}", OK_SCHEMA, Map.class);
    assertThat(next.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(next.options()).containsEntry("fallbackReason", FallbackChatModel.blockedReason(LlmFailureKind.EXHAUSTED));
    assertThat(claudeBodies).as("차단 중에는 Claude 를 부르지 않는다").hasSize(1);
    assertThat(alerts).as("알림은 하루 1통").hasSize(1);
    claudeServer.verify();
    openAiServer.verify();
  }

  @Test
  @DisplayName("키 오류(401)는 폴백하되 #hvy-error 로 알리고 30분 차단한다")
  void 키오류_폴백() {
    expectClaudeError(401, error("authentication_error", "invalid x-api-key", null));
    expectOpenAi(OPENAI_OK);

    MarketJudgeClient.CallResult<Map> result = judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class);

    assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(result.options()).containsEntry("fallbackReason", LlmFailureKind.MISCONFIGURED.name());
    assertThat(gate.currentBlock(CLAUDE_MODEL)).hasValueSatisfying(block -> assertThat(block.until()).isEqualTo(now.plus(Duration.ofMinutes(30))));
    assertThat(alertErrors).as("사람이 고쳐야 하는 문제는 #hvy-error(error=true)").containsExactly(true);
  }

  @Test
  @DisplayName("거부(refusal)는 OpenAI 로 넘기되 차단하지 않는다 — 공급자 장애가 아니라 요청 내용에 따른 것")
  void 거부_폴백() {
    expectClaude(CLAUDE_REFUSAL);
    expectOpenAi(OPENAI_OK);

    MarketJudgeClient.CallResult<Map> result = judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class);

    assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(result.options()).containsEntry("fallbackReason", FallbackChatModel.REASON_REFUSAL);
    assertThat(result.usage().getPromptTokens()).as("거부된 Claude 응답도 과금됐다 — 입력 81 + OpenAI 800").isEqualTo(881);
    assertThat(result.usage().getCompletionTokens()).isEqualTo(60);
    assertThat(result.reasoningTokens()).as("합산 뒤에도 추론 토큰이 사라지지 않는다").isEqualTo(40);
    assertThat(gate.isBlocked(CLAUDE_MODEL)).isFalse();
    assertThat(alerts).isEmpty();
    claudeServer.verify();
    openAiServer.verify();
  }

  @Test
  @DisplayName("모델 없음(404)은 폴백하되 그 모델만 30분 차단한다 — 다른 모델을 쓰는 역할은 차단되지 않고, 알림에 모델 ID 가 실린다")
  void 모델없음_모델단위_차단() {
    expectClaudeError(404, error("not_found_error", "model: " + CLAUDE_MODEL, null));
    expectOpenAi(OPENAI_OK);
    expectOpenAi(OPENAI_OK);

    MarketJudgeClient.CallResult<Map> result = judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class);

    assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(result.options()).containsEntry("fallbackReason", LlmFailureKind.MISCONFIGURED.name());
    assertThat(gate.currentBlock(CLAUDE_MODEL)).hasValueSatisfying(block -> {
      assertThat(block.scoped()).isTrue();
      assertThat(block.scope()).isEqualTo(CLAUDE_MODEL);
      assertThat(block.status()).isEqualTo(404);
      assertThat(block.until()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    });
    assertThat(gate.isBlocked("claude-chat")).as("채팅 모델을 쓰는 역할은 그대로 Claude 를 부른다").isFalse();
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0)).contains("범위: 모델 " + CLAUDE_MODEL);
    assertThat(alertErrors).as("모델 ID 는 사람이 고쳐야 한다").containsExactly(true);

    MarketJudgeClient.CallResult<Map> next = judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class);
    assertThat(next.options()).containsEntry("fallbackReason", FallbackChatModel.blockedReason(LlmFailureKind.MISCONFIGURED));
    assertThat(claudeBodies).as("차단 중에는 이 모델로 Claude 를 부르지 않는다").hasSize(1);
    claudeServer.verify();
    openAiServer.verify();
  }

  @Test
  @DisplayName("요청 오류(400)는 폴백하지 않고 그대로 실패한다 — 다른 공급자가 대신 답하면 요청 조립 버그가 가려진다")
  void 요청오류_폴백없음() {
    expectClaudeError(400, error("invalid_request_error", "output_config.format.schema: unsupported keyword minimum", null));

    assertThatThrownBy(() -> judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class))
        .isInstanceOfSatisfying(LlmProviderException.class, e -> {
          assertThat(e.getKind()).isEqualTo(LlmFailureKind.REQUEST_INVALID);
          assertThat(e.getStatus()).isEqualTo(400);
          assertThat(e.getMessage()).contains("unsupported keyword minimum");
        });

    assertThat(openAiBodies).isEmpty();
    assertThat(gate.isBlocked(CLAUDE_MODEL)).isFalse();
    assertThat(alerts).isEmpty();
    openAiServer.verify();
  }

  @Test
  @DisplayName("출력 절단(max_tokens)은 폴백하지 않는다 — 호출부가 공급자와 설정 키를 알려 주는 예외로 실패한다")
  void 출력절단_폴백없음() {
    expectClaude(CLAUDE_TRUNCATED);

    assertThatThrownBy(() -> judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("잘렸습니다")
        .hasMessageContaining("CLAUDE")
        .hasMessageContaining("advisor.claude");

    assertThat(openAiBodies).isEmpty();
    assertThat(gate.isBlocked(CLAUDE_MODEL)).isFalse();
    openAiServer.verify();
  }

  @Test
  @DisplayName("Claude 가 준비되지 않으면(키 없음·스위치 off) OpenAI 로만 부른다 — 폴백이 아니라 정상 경로라 사유를 싣지 않는다")
  void claude_미설정() {
    claudeReady.set(false);
    expectOpenAi(OPENAI_OK);

    MarketJudgeClient.CallResult<Map> result = judge.call("시스템 프롬프트", "{}", OK_SCHEMA, Map.class);

    assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(result.options()).doesNotContainKey("fallbackReason");
    assertThat(claudeBodies).isEmpty();
    assertThat(json(openAiBodies.get(0)).path("text").path("format").path("schema")).isEqualTo(json(OK_SCHEMA));
    claudeServer.verify();
    openAiServer.verify();
  }
}

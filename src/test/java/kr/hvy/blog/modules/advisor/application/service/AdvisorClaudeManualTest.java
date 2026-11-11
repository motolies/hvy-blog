package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesChatModel;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesClient;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeModelSettings;
import kr.hvy.blog.modules.advisor.client.claude.RestClientHttpClient;
import kr.hvy.blog.modules.advisor.client.llm.ApiKeyHeaderInterceptor;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/**
 * Claude 실계정 수동 실측 (ANTHROPIC_API_KEY·ADVISOR_CLAUDE_JUDGE_MODEL 환경변수가 있을 때만 — 없으면 스킵, 호출마다 과금).
 * 목 서버로는 확인할 수 없는 것만 본다:
 * <ol>
 *   <li>실제 스키마 5종을 {@code output_config.format} 이 받아들이는지(지원하지 않는 JSON Schema 키워드가 있으면 400) — 그리고 판단 1건의 지연이
 *       응답 타임아웃(advisor.claude.timeout-seconds 300초) 안인지</li>
 *   <li>도구 루프 2라운드가 400 없이 도는지 — thinking 블록·signature 를 그대로 되돌려 보내는 것(바뀌면 서버가 거부)과, 자동 캐싱으로 2라운드에
 *       cache_read 가 잡히는지</li>
 * </ol>
 * 조립은 운영 AdvisorAiConfig 의 Claude 모델과 같고 RestClient 만 api_log 인터셉터 없이 인증 인터셉터 + 읽기 타임아웃으로 만든다. 재시도는 1회.
 * <pre>ANTHROPIC_API_KEY=... ADVISOR_CLAUDE_JUDGE_MODEL=... ./gradlew test --tests "kr.hvy.blog.modules.advisor.application.service.AdvisorClaudeManualTest"</pre>
 */
class AdvisorClaudeManualTest {

  private static final int TIMEOUT_SECONDS = 300;

  private String model;
  private ClaudeMessagesClient client;

  /** 채팅 도구와 같은 모양의 가짜 도구 — 호출 여부만 기록한다 */
  static class FakeToolkit {

    final List<String> invocations = new ArrayList<>();

    @Tool(name = "stockSnapshot", description = "종목코드로 최근 종가를 조회한다")
    public Map<String, Object> stockSnapshot(@ToolParam(description = "6자리 종목코드") String code) {
      invocations.add(code);
      return Map.of("code", code, "close", 71_000, "asOf", "2026-11-10");
    }
  }

  @BeforeEach
  void setUp() {
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    model = System.getenv("ADVISOR_CLAUDE_JUDGE_MODEL");
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY 없음 — 수동 실측 스킵");
    Assumptions.assumeTrue(model != null && !model.isBlank(), "ADVISOR_CLAUDE_JUDGE_MODEL 없음 — 수동 실측 스킵");

    HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)); // 운영 claudeApiRestClient 의 응답 타임아웃과 같은 값
    RestClient restClient = RestClient.builder().requestFactory(factory)
        .requestInterceptor(ApiKeyHeaderInterceptor.header(RestClientHttpClient.API_KEY_HEADER, () -> apiKey)).build();
    client = new ClaudeMessagesClient(RestClientHttpClient.anthropicClient(restClient), 1);
  }

  @Test
  @DisplayName("실제 모델: output_config.format 이 판단 스키마(후보 enum·2단계 중첩)를 받아들이고 후보 안에서만 픽을 낸다 — 지연이 타임아웃 안이다")
  void realModelAcceptsAdviceSchema() {
    AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
    AdvisorProperties.Claude claude = properties.getClaude();
    ClaudeMessagesChatModel chatModel = new ClaudeMessagesChatModel(client,
        new ClaudeModelSettings(model, claude.getJudgeMaxTokens(), claude.getJudgeEffort(), false, 0));
    MarketJudgeClient judge = new MarketJudgeClient(ChatClient.builder(chatModel).build(), model);

    AdvicePromptBuilder builder = new AdvicePromptBuilder(properties);
    PromptPayload payload = builder.build(AdvicePromptBuilderTest.market(), AdvicePromptBuilderTest.screening(8), null, List.of(),
        Map.of("MOM_20D", 0.12, "FOREIGN_FLOW", 0.12), DataQuality.OK);
    String schema = AdviceSchemaFactory.schemaJson(payload.candidateTickers(), payload.sectorCodes());

    long started = System.currentTimeMillis();
    MarketJudgeClient.JudgeResult result = judge.judge(new PromptResources().adviceSystem(), payload, schema);
    long elapsedSeconds = (System.currentTimeMillis() - started) / 1000;

    System.out.println("=== 응답 ===\n" + result.rawText());
    System.out.println("=== " + elapsedSeconds + "초, usage in/out/reasoning/cached: " + result.usage().getPromptTokens() + "/"
        + result.usage().getCompletionTokens() + "/" + result.reasoningTokens() + "/" + result.cachedTokens() + ", " + result.options());
    assertThat(result.provider()).isEqualTo(LlmProvider.CLAUDE);
    assertThat(result.response().picks()).allMatch(p -> payload.candidateTickers().contains(p.ticker()));
    assertThat(result.response().picks()).allMatch(p -> AdviceSchemaFactory.CONVICTIONS.contains(p.conviction()));
    assertThat(result.response().trendOutlook()).as("trendOutlook 블록").isNotNull();
    assertThat(elapsedSeconds).as("응답 타임아웃(%d초)의 절반을 넘으면 effort 를 낮추거나 타임아웃을 올린다", TIMEOUT_SECONDS).isLessThan(TIMEOUT_SECONDS / 2);
  }

  @Test
  @DisplayName("실제 모델: 나머지 스키마 4종(아침·장기·오답노트·교훈)을 output_config.format 이 400 없이 받아들인다")
  void realModelAcceptsOtherSchemas() {
    AdvisorProperties.Claude claude = new AdvisorProperties(new MockEnvironment()).getClaude();
    ClaudeMessagesChatModel chatModel = new ClaudeMessagesChatModel(client,
        new ClaudeModelSettings(model, claude.getAssistMaxTokens(), "low", false, 0));
    MarketJudgeClient assist = new MarketJudgeClient(ChatClient.builder(chatModel).build(), model);
    List<String> tickers = List.of("005930", "000660");
    List<String> sectors = List.of("G25", "G45");
    Map<String, String> schemas = new LinkedHashMap<>();
    schemas.put("MorningAdviceSchemaFactory", MorningAdviceSchemaFactory.schemaJson(tickers, List.of("035420"), List.of("005930", "000660", "035420")));
    schemas.put("LongTermNarrativeSchemaFactory", LongTermNarrativeSchemaFactory.schemaJson(tickers));
    schemas.put("NoteSchemaFactory", NoteSchemaFactory.schemaJson(tickers, sectors));
    schemas.put("LessonSchemaFactory", LessonSchemaFactory.schemaJson(sectors));

    schemas.forEach((name, schema) -> {
      // 스키마 수용만 본다 — 내용은 무엇이든 스키마에 맞으면 된다
      MarketJudgeClient.CallResult<Map> result = assist.call("주어진 스키마에 맞는 최소한의 JSON 을 한국어로 채워 답하라. 지어낸 값이어도 된다.",
          "{\"note\":\"스키마 수용 실측\"}", schema, Map.class);
      System.out.println("=== " + name + ": in/out " + result.usage().getPromptTokens() + "/" + result.usage().getCompletionTokens() + " → " + result.rawText());
      assertThat(result.provider()).as(name).isEqualTo(LlmProvider.CLAUDE);
      assertThat(result.value()).as(name).isNotEmpty();
    });
  }

  @Test
  @DisplayName("실제 모델: 도구 루프 2라운드가 400 없이 돈다(thinking 블록 원형 재전송) — 2라운드에 캐시 읽기가 잡히는지 출력한다")
  void realToolLoop() {
    AdvisorProperties.Claude claude = new AdvisorProperties(new MockEnvironment()).getClaude();
    ClaudeMessagesChatModel chatModel = new ClaudeMessagesChatModel(client,
        new ClaudeModelSettings(model, claude.getChatMaxTokens(), claude.getChatEffort(), true, 12));
    ToolCallingManager manager = ToolCallingManager.builder().maxCallsPerTool(6).maxTotalToolCalls(12)
        .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE).build();
    ChatClient chatClient = ChatClient.builder(chatModel, ObservationRegistry.NOOP, null, null,
        ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true)).build();
    FakeToolkit toolkit = new FakeToolkit();

    ChatResponse response = chatClient.prompt()
        .system(new PromptResources().chatSystem())
        .user("stockSnapshot 도구로 005930 의 최근 종가를 조회해서 한 문장으로 알려줘.")
        .tools(toolkit)
        .call()
        .chatResponse();

    String text = response.getResult().getOutput().getText();
    System.out.println("=== 답: " + text);
    System.out.println("=== 도구 호출 " + toolkit.invocations + ", usage in/out/cacheRead: " + response.getMetadata().getUsage().getPromptTokens() + "/"
        + response.getMetadata().getUsage().getCompletionTokens() + "/" + response.getMetadata().getUsage().getCacheReadInputTokens());
    assertThat(toolkit.invocations).as("도구를 불렀다 = 2라운드까지 돌았다").isNotEmpty();
    assertThat(text).isNotBlank().contains("71");
    assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.CLAUDE);
  }
}

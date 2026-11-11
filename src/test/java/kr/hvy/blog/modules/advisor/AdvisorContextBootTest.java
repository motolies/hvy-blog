package kr.hvy.blog.modules.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Map;
import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.infra.config.AdvisorAiConfig;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatClient;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.chat.SlackChatRouter;
import kr.hvy.blog.modules.advisor.application.chat.SlackSocketModeRunner;
import kr.hvy.blog.modules.advisor.application.service.AdviseJob;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.application.service.WeeklyReviewJob;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesChatModel;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesClient;
import kr.hvy.blog.modules.advisor.client.llm.FallbackChatModel;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderGate;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * advisor.enabled=true 로 컨텍스트가 뜨는지 + OpenAI 배선이 역할별로 맞는지 검증한다.
 * <p>
 * 기본 부팅 테스트(BlogApplicationTests)는 advisor.enabled=false 라 @ConditionalOnProperty 빈이 등록조차 되지 않아, 2026-09-13 로컬 기동에서
 * 드러난 결함(잡 클래스의 생성자 2개·@Autowired 없음 → "No default constructor found"; 당시 SDK 빌더에 비동기 클라이언트 누락 → 기동 실패)을 잡지 못했다.
 * 2026-09-19 부터 OpenAI 는 SDK 없이 Responses API 모델 3개(judge/assist/chat)로 조립되며, Spring AI 스타터를 뺐으므로 이 테스트 통과가
 * "자동구성 없이도 기동된다" 의 증거다. OpenAI 는 호출하지 않는다 — WithApiKey 는 HTTP 클라이언트를 목으로 바꿔 요청만 캡처한다.
 * <p>
 * 2026-11-11 부터 ChatClient 는 폴백 래퍼(Claude 1순위 → OpenAI) 위에 있다. 테스트 프로파일은 Anthropic 키가 빈 값이라 기존 중첩 클래스들은
 * "Claude 미설정 → OpenAI 로만" 경로를 그대로 검증한다(래퍼가 끼어도 OpenAI 요청이 전과 같다 = 래퍼 투명성). Claude 가 켜진 배선은 WithClaudeKey 가 본다.
 */
@ActiveProfiles("test")
class AdvisorContextBootTest {

  static final String OK_BODY = """
      {"id":"resp_boot","status":"completed","model":"m",
       "output":[{"type":"message","id":"msg","role":"assistant","status":"completed",
                  "content":[{"type":"output_text","text":"{\\"ok\\":true}","annotations":[]}]}],
       "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
      """;
  static final String OK_SCHEMA = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}";

  /** 오케스트레이터·잡 2종·MarketJudgeClient 빈 2종·Responses 모델 3종이 모두 조립되어야 한다 */
  static void assertAdvisorBeansWired(ApplicationContext context) {
    assertThat(context.getBean(AdvisorOrchestrator.class)).isNotNull();
    assertThat(context.getBean(AdviseJob.class)).isNotNull();
    assertThat(context.getBean(WeeklyReviewJob.class)).isNotNull();
    assertThat(context.getBean(MarketJudgeClient.JUDGE_BEAN, MarketJudgeClient.class)).isNotNull();
    assertThat(context.getBean(MarketJudgeClient.ASSIST_BEAN, MarketJudgeClient.class)).isNotNull();
    assertThat(context.getBeansOfType(OpenAiResponsesChatModel.class)).as("역할별 Responses 모델 judge/assist/chat")
        .containsOnlyKeys(AdvisorAiConfig.JUDGE_MODEL, AdvisorAiConfig.ASSIST_MODEL, AdvisorAiConfig.CHAT_MODEL);
    assertThat(context.getBean("openAiRestClient", RestClient.class)).as("api_log 적재 RestClient").isNotNull();
    assertThat(context.getBeansOfType(ClaudeMessagesChatModel.class)).as("역할별 Claude 모델 judge/assist/chat")
        .containsOnlyKeys(AdvisorAiConfig.JUDGE_CLAUDE_MODEL, AdvisorAiConfig.ASSIST_CLAUDE_MODEL, AdvisorAiConfig.CHAT_CLAUDE_MODEL);
    assertThat(context.getBeansOfType(FallbackChatModel.class)).as("역할별 폴백 래퍼 — ChatClient 가 올라가는 모델")
        .containsOnlyKeys(AdvisorAiConfig.JUDGE_FALLBACK_MODEL, AdvisorAiConfig.ASSIST_FALLBACK_MODEL, AdvisorAiConfig.CHAT_FALLBACK_MODEL);
    assertThat(context.getBeansOfType(LlmProviderGate.class)).as("Claude 차단 게이트는 세 역할이 하나를 공유한다").hasSize(1);
    assertThat(context.getBean("claudeApiRestClient", RestClient.class)).as("Claude 용 api_log 적재 RestClient(OAuth ping 용 claudeRestClient 와 별개)").isNotNull();
    assertThat(context.getBean("claudeChatApiRestClient", RestClient.class)).as("채팅 전용 Claude RestClient — 응답 타임아웃이 짧다")
        .isNotSameAs(context.getBean("claudeApiRestClient", RestClient.class));
    assertThat(context.getBeansOfType(ClaudeMessagesClient.class)).as("Claude 클라이언트는 판단·보조용과 채팅용 둘")
        .containsOnlyKeys(AdvisorAiConfig.CLAUDE_CLIENT, AdvisorAiConfig.CHAT_CLAUDE_CLIENT);
  }

  static Message claudeMessage(String json) {
    try {
      return ObjectMappers.jsonMapper().readValue(json, Message.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 키가 있는 정상 경로 + 배선 검증. HTTP 클라이언트만 목으로 바꾸고 judge/assist 빈을 실제로 불러, 요청의 model·max_output_tokens·text.format 이
   * 각 역할의 프로퍼티와 일치하는지 본다 — assist 호출이 judge 모델·상한으로 나가던 오배선(2026-09-19)의 회귀 테스트.
   */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key=test-key",
      "advisor.model.judge=judge-x", "advisor.model.judge-max-completion-tokens=777",
      "advisor.model.assist=assist-x", "advisor.model.assist-max-completion-tokens=333"})
  class WithApiKey extends AbstractTestContainers {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private OpenAiResponsesClient responsesClient;

    /** Claude 클라이언트 빈이 둘(판단·보조용, 채팅용)이라 이름으로 목을 건다 */
    @MockitoBean(name = AdvisorAiConfig.CLAUDE_CLIENT)
    private ClaudeMessagesClient claudeClient;

    @MockitoBean(name = AdvisorAiConfig.CHAT_CLAUDE_CLIENT)
    private ClaudeMessagesClient chatClaudeClient;

    @Test
    void advisorBeansAreWired() {
      assertAdvisorBeansWired(context);
      AdvisorProperties properties = context.getBean(AdvisorProperties.class);
      assertThat(properties.claudeConfigured()).as("테스트 프로파일은 Anthropic 키가 빈 값").isFalse();
      assertThat(properties.openAiConfigured()).isTrue();
      assertThat(properties.isConfigured()).as("공급자 하나만 구성돼도 실행 가능").isTrue();
      OpenAiResponsesChatModel judge = context.getBean(AdvisorAiConfig.JUDGE_MODEL, OpenAiResponsesChatModel.class);
      OpenAiResponsesChatModel assist = context.getBean(AdvisorAiConfig.ASSIST_MODEL, OpenAiResponsesChatModel.class);
      OpenAiResponsesChatModel chat = context.getBean(AdvisorAiConfig.CHAT_MODEL, OpenAiResponsesChatModel.class);
      assertThat(judge.getOptions().getModel()).isEqualTo("judge-x");
      assertThat(judge.getOptions().getMaxTokens()).isEqualTo(777);
      assertThat(assist.getOptions().getModel()).isEqualTo("assist-x");
      assertThat(assist.getOptions().getMaxTokens()).isEqualTo(333);
      assertThat(chat.getOptions().getModel()).as("advisor.chat.model 이 비면 assist 모델").isEqualTo(properties.getModel().getAssist());
    }

    @Test
    void judgeAndAssistCallsCarryTheirOwnModel() {
      when(responsesClient.create(any())).thenReturn(AdvisorJson.read(OK_BODY, ResponsesResponse.class));

      context.getBean(MarketJudgeClient.ASSIST_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);
      context.getBean(MarketJudgeClient.JUDGE_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);

      ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
      verify(responsesClient, times(2)).create(captor.capture());
      ResponsesRequest assistRequest = captor.getAllValues().get(0);
      ResponsesRequest judgeRequest = captor.getAllValues().get(1);
      assertThat(assistRequest.model()).as("assist 빈은 assist 모델로 나간다").isEqualTo("assist-x");
      assertThat(assistRequest.maxOutputTokens()).isEqualTo(333);
      assertThat(judgeRequest.model()).isEqualTo("judge-x");
      assertThat(judgeRequest.maxOutputTokens()).isEqualTo(777);
      for (ResponsesRequest request : captor.getAllValues()) {
        assertThat(request.text().format().strict()).isTrue();
        assertThat(request.text().format().name()).isEqualTo("Map");
        assertThat(request.store()).isFalse();
        assertThat(request.tools()).isNull();
      }
      verifyNoInteractions(claudeClient, chatClaudeClient);
    }
  }

  /**
   * Claude 키가 있는 배선(2026-11-11). judge/assist 빈이 Claude 로 나가고 요청의 model·max_tokens·effort·스키마가 각 역할의 프로퍼티와 일치하는지,
   * Claude 가 소진으로 실패하면 같은 빈이 OpenAI 역할 모델로 폴백하는지 본다. 두 공급자의 클라이언트만 목이다.
   */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key=test-key", "spring.ai.anthropic.api-key=claude-test-key",
      "advisor.model.judge=judge-x", "advisor.model.judge-max-completion-tokens=777",
      "advisor.model.assist=assist-x", "advisor.model.assist-max-completion-tokens=333",
      "advisor.claude.judge=claude-judge-x", "advisor.claude.judge-max-tokens=1234", "advisor.claude.judge-effort=high",
      "advisor.claude.assist=claude-assist-x", "advisor.claude.assist-max-tokens=567", "advisor.claude.assist-effort=low"})
  class WithClaudeKey extends AbstractTestContainers {

    static final String CLAUDE_OK = """
        {"id":"msg_boot","type":"message","role":"assistant","model":"claude-m",
         "content":[{"type":"text","text":"{\\"ok\\":true}"}],
         "stop_reason":"end_turn","stop_sequence":null,
         "usage":{"input_tokens":1,"output_tokens":1}}
        """;

    @Autowired
    private ApplicationContext context;

    @MockitoBean(name = AdvisorAiConfig.CLAUDE_CLIENT)
    private ClaudeMessagesClient claudeClient;

    @MockitoBean(name = AdvisorAiConfig.CHAT_CLAUDE_CLIENT)
    private ClaudeMessagesClient chatClaudeClient;

    @MockitoBean
    private OpenAiResponsesClient responsesClient;

    /** 게이트는 컨텍스트에 하나라 앞 테스트의 차단이 남는다 — 테스트마다 비운다(이 클래스의 테스트가 만드는 계정 단위·채팅 범위 차단) */
    @BeforeEach
    void clearGate() {
      LlmProviderGate gate = context.getBean(LlmProviderGate.class);
      gate.recordSuccess(gate.beginCall(), AdvisorAiConfig.CHAT_UNANSWERED_SCOPE);
    }

    @Test
    void advisorBeansAreWired() {
      assertAdvisorBeansWired(context);
      AdvisorProperties properties = context.getBean(AdvisorProperties.class);
      assertThat(properties.claudeConfigured()).isTrue();
      assertThat(properties.openAiConfigured()).isTrue();
    }

    @Test
    void judgeAndAssistCallsGoToClaudeWithTheirOwnSettings() {
      when(claudeClient.create(any())).thenReturn(claudeMessage(CLAUDE_OK));

      MarketJudgeClient.CallResult<Map> assist = context.getBean(MarketJudgeClient.ASSIST_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);
      MarketJudgeClient.CallResult<Map> judge = context.getBean(MarketJudgeClient.JUDGE_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);

      assertThat(assist.provider()).isEqualTo(LlmProvider.CLAUDE);
      assertThat(judge.provider()).isEqualTo(LlmProvider.CLAUDE);
      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(claudeClient, times(2)).create(captor.capture());
      MessageCreateParams assistRequest = captor.getAllValues().get(0);
      MessageCreateParams judgeRequest = captor.getAllValues().get(1);
      assertThat(assistRequest.model().asString()).as("assist 빈은 Claude assist 모델로 나간다").isEqualTo("claude-assist-x");
      assertThat(assistRequest.maxTokens()).isEqualTo(567L);
      assertThat(assistRequest.outputConfig().orElseThrow().effort().orElseThrow().asString()).isEqualTo("low");
      assertThat(judgeRequest.model().asString()).isEqualTo("claude-judge-x");
      assertThat(judgeRequest.maxTokens()).isEqualTo(1234L);
      assertThat(judgeRequest.outputConfig().orElseThrow().effort().orElseThrow().asString()).isEqualTo("high");
      for (MessageCreateParams request : captor.getAllValues()) {
        assertThat(request.outputConfig().orElseThrow().format()).as("구조화 출력 스키마").isPresent();
        assertThat(request.tools()).as("판단 호출에는 도구가 없다").isEmpty();
      }
      verifyNoInteractions(responsesClient, chatClaudeClient);
    }

    /**
     * 채팅 모델은 채팅용 Claude 클라이언트(짧은 타임아웃·재시도 1회 이하)로 나가야 한다 — 판단용 클라이언트가 꽂히면 Claude 타임아웃이 채팅의
     * 도구 마감을 넘겨 OpenAI 폴백의 도구 호출이 전부 마감 오류가 된다.
     */
    @Test
    void chatModelUsesTheChatClaudeClient() {
      when(chatClaudeClient.create(any())).thenReturn(claudeMessage(CLAUDE_OK));

      ChatResponse response = context.getBean(AdvisorAiConfig.CHAT_FALLBACK_MODEL, FallbackChatModel.class).call(new Prompt("안녕"));

      assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.CLAUDE);
      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(chatClaudeClient).create(captor.capture());
      assertThat(captor.getValue().model().asString()).as("advisor.claude.chat")
          .isEqualTo(context.getBean(AdvisorProperties.class).getClaude().chatOrAssist());
      verifyNoInteractions(claudeClient, responsesClient);
    }

    /**
     * 채팅 래퍼 빈은 응답 없음을 채팅 범위로만 차단해야 한다 — 배선에서 범위 인자가 빠지면 느린 채팅 한 번(90초 타임아웃)이 5분간
     * 판단·보조 호출까지 OpenAI 로 내려보낸다.
     */
    @Test
    void chatTimeoutBlocksOnlyTheChatRole() {
      when(chatClaudeClient.create(any()))
          .thenThrow(new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.UNAVAILABLE, 0, null, "응답 없음(타임아웃·연결 실패): Read timed out", null));
      when(responsesClient.create(any())).thenReturn(AdvisorJson.read(OK_BODY, ResponsesResponse.class));
      when(claudeClient.create(any())).thenReturn(claudeMessage(CLAUDE_OK));
      FallbackChatModel chat = context.getBean(AdvisorAiConfig.CHAT_FALLBACK_MODEL, FallbackChatModel.class);

      assertThat(LlmResponses.fallbackReason(chat.call(new Prompt("안녕")))).isEqualTo(LlmFailureKind.UNAVAILABLE.name());
      assertThat(LlmResponses.fallbackReason(chat.call(new Prompt("안녕")))).as("채팅은 차단 중 — Claude 를 다시 부르지 않는다")
          .isEqualTo(FallbackChatModel.blockedReason(LlmFailureKind.UNAVAILABLE));
      verify(chatClaudeClient, times(1)).create(any());

      MarketJudgeClient.CallResult<Map> judge = context.getBean(MarketJudgeClient.JUDGE_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);
      assertThat(judge.provider()).as("판단은 차단되지 않고 Claude 로 간다").isEqualTo(LlmProvider.CLAUDE);
      assertThat(judge.options()).doesNotContainKey("fallbackReason");
    }

    @Test
    void exhaustedClaudeFallsBackToOpenAiRoleModel() {
      when(claudeClient.create(any())).thenThrow(new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.EXHAUSTED, 402, null, "credit balance", null));
      when(responsesClient.create(any())).thenReturn(AdvisorJson.read(OK_BODY, ResponsesResponse.class));

      MarketJudgeClient.CallResult<Map> assist = context.getBean(MarketJudgeClient.ASSIST_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);

      assertThat(assist.provider()).isEqualTo(LlmProvider.OPENAI);
      assertThat(assist.options()).containsEntry("fallbackReason", LlmFailureKind.EXHAUSTED.name());
      ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
      verify(responsesClient).create(captor.capture());
      assertThat(captor.getValue().model()).as("assist 의 폴백은 OpenAI assist 모델").isEqualTo("assist-x");
      assertThat(captor.getValue().maxOutputTokens()).isEqualTo(333);
      assertThat(captor.getValue().text().format().strict()).isTrue();
      assertThat(context.getBean(LlmProviderGate.class).isBlocked("claude-judge-x")).as("소진은 계정 단위로 차단한다 — 다른 모델을 쓰는 judge·chat 도 OpenAI 로 간다")
          .isTrue();

      // 같은 게이트를 judge 가 공유한다: Claude 를 다시 부르지 않고 OpenAI judge 모델로
      MarketJudgeClient.CallResult<Map> judge = context.getBean(MarketJudgeClient.JUDGE_BEAN, MarketJudgeClient.class).call("s", "{}", OK_SCHEMA, Map.class);
      assertThat(judge.provider()).isEqualTo(LlmProvider.OPENAI);
      assertThat(judge.options()).containsEntry("fallbackReason", FallbackChatModel.blockedReason(LlmFailureKind.EXHAUSTED));
      verify(claudeClient, times(1)).create(any());
    }
  }

  /**
   * Slack 채팅 봇(chat-v1)을 켰지만 app-level 토큰·채널·허용 사용자가 없는 경우 — 러너·라우터 빈은 조립되되 Socket Mode 연결은 열지 않고 기동은 성공해야 한다.
   * 잘못된 Slack 설정으로 블로그 전체가 안 뜨면 안 된다는 AdvisorChatProperties 의 계약이 이 테스트의 존재 이유다.
   */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key=test-key", "advisor.chat.enabled=true", "advisor.chat.app-token="})
  class WithChatEnabledButUnconfigured extends AbstractTestContainers {

    @Autowired
    private ApplicationContext context;

    @Test
    void chatBeansAreWiredButSocketModeIsNotStarted() {
      assertAdvisorBeansWired(context);
      assertThat(context.getBean(SlackChatRouter.class)).isNotNull();
      assertThat(context.getBean(AdvisorChatClient.class)).as("도구 루프 답변자(chatChatClient 빈 + toolkit 4종)").isNotNull();
      assertThat(context.getBean(AdvisorAiConfig.CHAT_MODEL, OpenAiResponsesChatModel.class)).as("채팅용 Responses API ChatModel(2026-09-19)").isNotNull();
      SlackSocketModeRunner runner = context.getBean(SlackSocketModeRunner.class);
      assertThat(runner.isAutoStartup()).isFalse();
      assertThat(runner.isRunning()).isFalse();
    }
  }

  /**
   * 키가 비어 있어도 기동은 되어야 한다(AdvisorAiConfig 의 계약: 잡 실행 시 isConfigured 로 거부, 기동 실패 아님).
   * 개발자 셸에 OPENAI_API_KEY 가 있어도 인라인 프로퍼티가 우선하므로 빈 키 경로가 실제로 검증된다.
   */
  @Nested
  @SpringBootTest(properties = {"advisor.enabled=true", "spring.ai.openai.api-key="})
  class WithoutApiKey extends AbstractTestContainers {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsAndAdvisorBeansAreWired() {
      assertAdvisorBeansWired(context);
      assertThat(context.getBean(AdvisorProperties.class).isConfigured()).as("두 공급자 모두 키가 없으면 잡 실행을 거부한다").isFalse();
    }

    /** 목이 없는 컨텍스트에서 실제 클라이언트 빈의 재시도 횟수를 본다 — 채팅용은 yml 값(2)이 아니라 1 로 묶인다 */
    @Test
    void chatClaudeClientRetriesAtMostOnce() {
      AdvisorProperties.Claude claude = context.getBean(AdvisorProperties.class).getClaude();
      ClaudeMessagesClient judgeClient = context.getBean(AdvisorAiConfig.CLAUDE_CLIENT, ClaudeMessagesClient.class);
      ClaudeMessagesClient chatClient = context.getBean(AdvisorAiConfig.CHAT_CLAUDE_CLIENT, ClaudeMessagesClient.class);

      assertThat(ReflectionTestUtils.getField(judgeClient, "maxRetries")).isEqualTo(claude.getMaxRetries());
      assertThat(ReflectionTestUtils.getField(chatClient, "maxRetries")).isEqualTo(Math.min(claude.getMaxRetries(), 1));
      assertThat(claude.getChatTimeoutSeconds()).as("채팅 타임아웃은 채팅의 도구 마감보다 짧아야 한다")
          .isLessThan(context.getBean(AdvisorChatProperties.class).getAnswerTimeoutSeconds());
    }
  }
}

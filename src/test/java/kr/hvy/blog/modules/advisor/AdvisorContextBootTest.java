package kr.hvy.blog.modules.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.infra.config.AdvisorAiConfig;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatClient;
import kr.hvy.blog.modules.advisor.application.chat.SlackChatRouter;
import kr.hvy.blog.modules.advisor.application.chat.SlackSocketModeRunner;
import kr.hvy.blog.modules.advisor.application.service.AdviseJob;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.application.service.WeeklyReviewJob;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * advisor.enabled=true 로 컨텍스트가 뜨는지 + OpenAI 배선이 역할별로 맞는지 검증한다.
 * <p>
 * 기본 부팅 테스트(BlogApplicationTests)는 advisor.enabled=false 라 @ConditionalOnProperty 빈이 등록조차 되지 않아, 2026-09-13 로컬 기동에서
 * 드러난 결함(잡 클래스의 생성자 2개·@Autowired 없음 → "No default constructor found"; 당시 SDK 빌더에 비동기 클라이언트 누락 → 기동 실패)을 잡지 못했다.
 * 2026-09-19 부터 OpenAI 는 SDK 없이 Responses API 모델 3개(judge/assist/chat)로 조립되며, Spring AI 스타터를 뺐으므로 이 테스트 통과가
 * "자동구성 없이도 기동된다" 의 증거다. OpenAI 는 호출하지 않는다 — WithApiKey 는 HTTP 클라이언트를 목으로 바꿔 요청만 캡처한다.
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

    @Test
    void advisorBeansAreWired() {
      assertAdvisorBeansWired(context);
      AdvisorProperties properties = context.getBean(AdvisorProperties.class);
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
    }
  }
}

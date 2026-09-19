package kr.hvy.blog.infra.config;

import io.micrometer.observation.ObservationRegistry;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatClient;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * advisor 모듈의 OpenAI 조립 — Responses API 클라이언트 1개(공유) + 역할별 ChatModel 3개(judge 판단 / assist 보조 / chat Slack 채팅 봇) + 그 위의
 * ChatClient 3종 + MarketJudgeClient 2종. OpenAI 공식 SDK·Spring AI OpenAI 모듈은 쓰지 않는다 — 모든 호출이 {@code openAiRestClient} 를 지나
 * {@code tb_api_log} 에 남고, 재시도(429·408·409·5xx·네트워크, Retry-After ≤30s)는 {@link OpenAiResponsesClient} 가 {@code advisor.model.max-retries} 만큼 직접 한다.
 * 소진되면 잡이 그날 판단을 건너뛴다.
 * <p>
 * <b>ChatModel 을 역할별로 나누는 이유(2026-09-19 오배선 수정).</b> Spring AI 2.0.1 ChatClient 는 요청 옵션을 "ChatModel 기본 옵션 + 요청 customizer" 로
 * 만든다. {@code ChatClient.Builder.defaultOptions(...)} 는 그 customizer 에 저장될 뿐이고 요청의 {@code .options(...)} 가 customizer 를 통째로 교체하므로,
 * ChatModel 하나를 judge/assist 가 공유하며 ChatClient 의 defaultOptions 로 모델을 갈라 두던 이전 구조에서는 MarketJudgeClient 가 responseFormat 을
 * {@code .options()} 로 넘길 때 assist 의 model·토큰 상한이 버려지고 judge 값으로 나갔다. 기본 옵션을 모델에 두면 어떤 customizer 를 얹어도 base 가 산다.
 * <p>
 * Spring AI 자동구성은 쓰지 않는다(스타터 미포함). {@code advisor.enabled=true} 일 때만 여기서 직접 조립하고, 키가 비어 있어도 기동은 된다 — 잡 실행 시
 * {@code AdvisorProperties.isConfigured} 가 거부한다(AdvisorContextBootTest). 모델 ID 는 yml(advisor.model.*)에서만 오고 코드에 박지 않는다
 * (ClaudeCodeRefreshService 의 하드코딩 모델이 은퇴로 404 를 냈던 전례).
 */
@Configuration
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorAiConfig {

  public static final String JUDGE = "judgeChatClient";
  public static final String ASSIST = "assistChatClient";
  /** 역할별 Responses ChatModel 빈 이름 — 소비자는 이 클래스와 부팅 테스트뿐이라 여기 둔다 */
  public static final String JUDGE_MODEL = "judgeResponsesChatModel";
  public static final String ASSIST_MODEL = "assistResponsesChatModel";
  public static final String CHAT_MODEL = "chatResponsesChatModel";

  /**
   * Responses API HTTP 클라이언트(3모델 공유). openAiRestClient 는 api_log 적재·전송 재시도 off 라 재시도는 이 클라이언트가 한다.
   */
  @Bean
  public OpenAiResponsesClient openAiResponsesClient(@Qualifier("openAiRestClient") RestClient openAiRestClient, AdvisorProperties properties) {
    return new OpenAiResponsesClient(openAiRestClient, properties.getModel().getMaxRetries());
  }

  /**
   * 판단용 ChatModel — 상위 모델, 출력 상한은 추론 토큰을 포함하는 max_output_tokens. 도구가 없어 강제 마무리는 끈다(0).
   */
  @Bean(JUDGE_MODEL)
  public OpenAiResponsesChatModel judgeResponsesChatModel(OpenAiResponsesClient client, AdvisorProperties properties) {
    AdvisorProperties.Model model = properties.getModel();
    return new OpenAiResponsesChatModel(client, chatOptions(model.getJudge(), model.getJudgeMaxCompletionTokens(), model.getJudgeTemperature()).build(), 0);
  }

  /**
   * 보조용 ChatModel(교훈 후보 생성·재현성 측정 요약) — 저가 모델.
   */
  @Bean(ASSIST_MODEL)
  public OpenAiResponsesChatModel assistResponsesChatModel(OpenAiResponsesClient client, AdvisorProperties properties) {
    AdvisorProperties.Model model = properties.getModel();
    return new OpenAiResponsesChatModel(client, chatOptions(model.getAssist(), model.getAssistMaxCompletionTokens(), model.getAssistTemperature()).build(), 0);
  }

  /**
   * Slack 채팅 봇용 ChatModel. 모델은 advisor.chat.model(비면 assist 모델), reasoning.effort 는 비면 미전송(서버 기본).
   * 도구 결과가 max-total-tool-calls 이상 쌓이면 tool_choice=none 으로 답을 강제한다(상한 초과 뒤 무한 루프 방지).
   */
  @Bean(CHAT_MODEL)
  public OpenAiResponsesChatModel chatResponsesChatModel(OpenAiResponsesClient client, AdvisorProperties properties, AdvisorChatProperties chat) {
    String model = StringUtils.defaultIfBlank(chat.getModel(), properties.getModel().getAssist());
    return new OpenAiResponsesChatModel(client,
        chatOptions(model, chat.getMaxCompletionTokens(), chat.getTemperature(), chat.reasoningEffortOrNull()).build(),
        Math.max(1, chat.getMaxTotalToolCalls()));
  }

  /**
   * 판단용 ChatClient. 옵션은 모델 것이 base 라 defaultOptions 를 두지 않는다. 관측 레지스트리는 의도적으로 기본(NOOP) — HTTP 관측은 RestClient 계층이 한다.
   */
  @Bean(JUDGE)
  public ChatClient judgeChatClient(@Qualifier(JUDGE_MODEL) OpenAiResponsesChatModel judgeModel) {
    return ChatClient.builder(judgeModel).build();
  }

  /**
   * 보조용 ChatClient (채점 요약·교훈 후보 생성·재현성 측정).
   */
  @Bean(ASSIST)
  public ChatClient assistChatClient(@Qualifier(ASSIST_MODEL) OpenAiResponsesChatModel assistModel) {
    return ChatClient.builder(assistModel).build();
  }

  /**
   * Slack 채팅 봇용 ChatClient (chat-v1, 2026-09-13) — 자유 텍스트 + 스레드 히스토리 + 도구 루프. 모델 파라미터는 반드시 @Qualifier 로 받는다(3빈이라
   * 파라미터 이름 폴백에 기대면 이름이 바뀔 때 judge 모델이 채팅에 꽂힌다).
   * <p>
   * Spring AI 2.0 에는 반복 횟수 옵션이 없고 ToolCallingManager 의 도구별·총합 호출 상한(기본 40/150 은 Slack 질문 하나에 과함)으로 건다.
   * 상한 초과는 RETURN_ERROR_RESPONSE — THROW 면 예외가 되어 사용자가 아무 답도 못 받지만, 오류 ToolResponse 로 돌려주면 모델이 "지금까지 얻은 값으로
   * 답한다" 로 마무리한다. DefaultChatClient 가 ToolCallingAdvisor 를 자동 등록하므로 커스텀 매니저는 5-인자 builder 오버로드로 넘긴다(중복 advisor 금지).
   */
  @Bean(AdvisorChatClient.CHAT_BEAN)
  public ChatClient chatChatClient(@Qualifier(CHAT_MODEL) OpenAiResponsesChatModel chatModel, AdvisorChatProperties chat,
      ObjectProvider<ObservationRegistry> observationRegistry) {
    ToolCallingManager manager = ToolCallingManager.builder()
        .maxCallsPerTool(Math.max(1, chat.getMaxCallsPerTool()))
        .maxTotalToolCalls(Math.max(1, chat.getMaxTotalToolCalls()))
        .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
        .build();
    ObservationRegistry observations = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    return ChatClient.builder(chatModel, observations, null, null,
            ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true))
        .build();
  }

  /**
   * 판단용 MarketJudgeClient 빈. 잡 클래스(AdviseJob·WeeklyReviewJob)가 ChatClient 를 받아 생성자 안에서 조립하던 것을 빈으로 올렸다 —
   * 그 방식은 생성자가 2개(Spring 용·테스트 용)가 되어 Spring 이 기본 생성자로 후퇴하다 기동에 실패했다(2026-09-13 ADVISOR_ENABLED=true 로컬 기동).
   * 빈 이름은 잡의 @Qualifier 와 같은 상수(MarketJudgeClient.JUDGE_BEAN)를 쓴다.
   */
  @Bean(MarketJudgeClient.JUDGE_BEAN)
  public MarketJudgeClient judgeClient(@Qualifier(JUDGE) ChatClient judgeChatClient, AdvisorProperties properties) {
    return new MarketJudgeClient(judgeChatClient, properties.getModel().getJudge());
  }

  /**
   * 보조용 MarketJudgeClient 빈 (교훈 후보 생성·재현성 측정) — assist 모델.
   */
  @Bean(MarketJudgeClient.ASSIST_BEAN)
  public MarketJudgeClient assistClient(@Qualifier(ASSIST) ChatClient assistChatClient, AdvisorProperties properties) {
    return new MarketJudgeClient(assistChatClient, properties.getModel().getAssist());
  }

  /**
   * 모델별 기본 옵션 빌더. temperature 는 null 이 아닐 때만 넣는다(추론 모델은 temperature 를 거부한다). maxTokens 가 Responses max_output_tokens.
   */
  static ResponsesChatOptions.Builder chatOptions(String model, int maxOutputTokens, Double temperature) {
    return chatOptions(model, maxOutputTokens, temperature, null);
  }

  /**
   * reasoningEffort 까지 받는 오버로드(채팅 봇용). null·공백이면 넣지 않는다 — 미전송 = OpenAI 서버 기본값.
   * 요청 customizer 병합(combineWith)은 non-null 값만 덮으므로 여기서 안 넣은 값은 그대로 "미설정" 으로 남는다.
   */
  static ResponsesChatOptions.Builder chatOptions(String model, int maxOutputTokens, Double temperature, String reasoningEffort) {
    ResponsesChatOptions.Builder builder = ResponsesChatOptions.builder()
        .model(model)
        .maxTokens(maxOutputTokens);
    if (temperature != null) {
      builder.temperature(temperature);
    }
    if (StringUtils.isNotBlank(reasoningEffort)) {
      builder.reasoningEffort(reasoningEffort);
    }
    return builder;
  }
}

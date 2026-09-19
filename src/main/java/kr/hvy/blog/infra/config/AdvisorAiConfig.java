package kr.hvy.blog.infra.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatClient;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * advisor 모듈의 OpenAI ChatModel 2개(judge/assist 가 공유하는 Chat Completions 모델, chat 전용 Responses 모델) + ChatClient 3종(judge 판단용 /
 * assist 보조용 / chat Slack 채팅 봇용) + 그 위의 MarketJudgeClient 2종.
 * <p>
 * 채팅 봇만 Responses API 다 — GPT-5.4 이상은 Chat Completions 에서 도구 호출 시 reasoning_effort=none 만 허용해 도구 14종을 붙이는 채팅이 400 으로
 * 죽었다(2026-09-19). judge/assist 는 도구가 없어 그대로 두고, Responses 모델은 openAiRestClient(tb_api_log 적재) 위에서 돈다.
 * <p>
 * Spring AI 2.0 의 OpenAI 자동구성(OpenAiChatAutoConfiguration)은 키가 비어 있으면 기동 자체를 막으므로 yml 에서
 * {@code spring.ai.model.chat=none} 으로 끄고, {@code advisor.enabled=true} 일 때만 여기서 직접 조립한다(로컬·테스트는 키 없이 기동).
 * 모델 ID 는 yml(advisor.model.*)에서만 오고 코드에 박지 않는다(ClaudeCodeRefreshService 의 하드코딩 모델이 은퇴로 404 를 냈던 전례).
 * 재시도는 OpenAI SDK 의 maxRetries(429·5xx·타임아웃)가 담당하고, 소진되면 잡이 그날 판단을 건너뛴다.
 */
@Configuration
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorAiConfig {

  public static final String JUDGE = "judgeChatClient";
  public static final String ASSIST = "assistChatClient";

  /**
   * OpenAI 공식 SDK 클라이언트 위의 Spring AI ChatModel. HTTP 는 Spring AI 가 제공하는 OkHttp 래퍼(관측 연동)를 쓴다.
   * 키가 비어 있으면 빈 생성 시점에 실패시키지 않고 호출 시점에 401 로 드러나게 둔다 — 기동 실패보다 잡 단위 실패가 낫다
   * (AdvisorProperties.isConfigured 가 트리거 전에 거부하므로 실제로는 호출까지 가지 않는다). 이 계약은 AdvisorContextBootTest 가 검증한다.
   * <p>
   * 동기·비동기 클라이언트를 **둘 다** 넘겨야 한다. Spring AI 2.0.1 의 {@code OpenAiChatModel.Builder.build()} 는 비동기 클라이언트가 없으면
   * {@code OpenAiSetup.setupAsyncClient} 로 자기 기본값(환경변수 OPENAI_API_KEY)에서 새로 조립하는데, 그 경로는 yml 의 키를 모르고
   * 환경변수가 없으면 "At least one credential source must be specified" 로 기동이 죽는다(2026-09-13 부팅 테스트에서 발견).
   */
  @Bean("advisorChatModel")
  public OpenAiChatModel advisorChatModel(AdvisorProperties properties, ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<MeterRegistry> meterRegistry) {
    AdvisorProperties.Model model = properties.getModel();
    Duration timeout = Duration.ofSeconds(model.getTimeoutSeconds());
    ObservationRegistry observations = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);

    SpringAiOpenAiHttpClient.Builder http = SpringAiOpenAiHttpClient.builder()
        .timeout(timeout)
        .observationRegistry(observations);
    MeterRegistry meters = meterRegistry.getIfAvailable();
    if (meters != null) {
      http.meterRegistry(meters);
    }
    ClientOptions options = ClientOptions.builder()
        .httpClient(http.build())
        .apiKey(properties.openAiApiKey())
        .timeout(timeout)
        .maxRetries(model.getMaxRetries())
        .build();
    OpenAIClient client = new OpenAIClientImpl(options);

    OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
        .openAiClient(client)
        .openAiClientAsync(client.async())
        .options(chatOptions(model.getJudge(), model.getJudgeMaxCompletionTokens(), model.getJudgeTemperature()).build())
        .observationRegistry(observations);
    if (meters != null) {
      builder.meterRegistry(meters);
    }
    return builder.build();
  }

  /**
   * 판단용 ChatClient. 출력 상한은 추론 토큰을 포함하는 maxCompletionTokens 로 건다(maxTokens 와 배타).
   */
  @Bean(JUDGE)
  public ChatClient judgeChatClient(OpenAiChatModel advisorChatModel, AdvisorProperties properties) {
    AdvisorProperties.Model model = properties.getModel();
    return ChatClient.builder(advisorChatModel)
        .defaultOptions(chatOptions(model.getJudge(), model.getJudgeMaxCompletionTokens(), model.getJudgeTemperature()))
        .build();
  }

  /**
   * 보조용 ChatClient (채점 요약·교훈 후보 생성·재현성 측정) — 저가 모델.
   */
  @Bean(ASSIST)
  public ChatClient assistChatClient(OpenAiChatModel advisorChatModel, AdvisorProperties properties) {
    AdvisorProperties.Model model = properties.getModel();
    return ChatClient.builder(advisorChatModel)
        .defaultOptions(chatOptions(model.getAssist(), model.getAssistMaxCompletionTokens(), model.getAssistTemperature()))
        .build();
  }

  /**
   * Slack 채팅 봇용 Responses API ChatModel. HTTP 는 openAiRestClient(api_log 적재·전송 재시도 off)이고 재시도는 클라이언트가 advisor.model.max-retries 만큼 직접 한다.
   * 기본 옵션은 judge/assist 와 같은 {@link OpenAiChatOptions} 라 아래 chatChatClient 의 defaultOptions 병합·.tools()·.toolContext() 배선이 그대로 동작한다.
   * 도구 결과가 max-total-tool-calls 이상 쌓이면 tool_choice=none 으로 답을 강제한다(상한 초과 뒤 무한 루프 방지).
   */
  @Bean
  public OpenAiResponsesChatModel openAiResponsesChatModel(@Qualifier("openAiRestClient") RestClient openAiRestClient, AdvisorProperties properties,
      AdvisorChatProperties chat) {
    String model = StringUtils.defaultIfBlank(chat.getModel(), properties.getModel().getAssist());
    OpenAiResponsesClient client = new OpenAiResponsesClient(openAiRestClient, properties.getModel().getMaxRetries());
    return new OpenAiResponsesChatModel(client,
        chatOptions(model, chat.getMaxCompletionTokens(), chat.getTemperature(), chat.reasoningEffortOrNull()).build(),
        Math.max(1, chat.getMaxTotalToolCalls()));
  }

  /**
   * Slack 채팅 봇용 ChatClient (chat-v1, 2026-09-13) — 자유 텍스트 + 스레드 히스토리 + 도구 루프. 모델은 advisor.chat.model, 비면 assist 모델.
   * 2026-09-19 부터 Responses API 모델 위에서 돈다(위 openAiResponsesChatModel).
   * <p>
   * Spring AI 2.0 에는 반복 횟수 옵션이 없고 ToolCallingManager 의 도구별·총합 호출 상한(기본 40/150 은 Slack 질문 하나에 과함)으로 건다.
   * 상한 초과는 RETURN_ERROR_RESPONSE — THROW 면 예외가 되어 사용자가 아무 답도 못 받지만, 오류 ToolResponse 로 돌려주면 모델이 "지금까지 얻은 값으로
   * 답한다" 로 마무리한다. DefaultChatClient 가 ToolCallingAdvisor 를 자동 등록하므로 커스텀 매니저는 5-인자 builder 오버로드로 넘긴다(중복 advisor 금지).
   */
  @Bean(AdvisorChatClient.CHAT_BEAN)
  public ChatClient chatChatClient(OpenAiResponsesChatModel openAiResponsesChatModel, AdvisorProperties properties, AdvisorChatProperties chat,
      ObjectProvider<ObservationRegistry> observationRegistry) {
    ToolCallingManager manager = ToolCallingManager.builder()
        .maxCallsPerTool(Math.max(1, chat.getMaxCallsPerTool()))
        .maxTotalToolCalls(Math.max(1, chat.getMaxTotalToolCalls()))
        .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
        .build();
    String model = StringUtils.defaultIfBlank(chat.getModel(), properties.getModel().getAssist());
    ObservationRegistry observations = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    return ChatClient.builder(openAiResponsesChatModel, observations, null, null,
            ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true))
        .defaultOptions(chatOptions(model, chat.getMaxCompletionTokens(), chat.getTemperature(), chat.reasoningEffortOrNull()))
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
   * 모델별 기본 옵션 빌더. temperature 는 null 이 아닐 때만 넣는다(추론 모델은 temperature 를 거부한다).
   */
  static OpenAiChatOptions.Builder chatOptions(String model, int maxCompletionTokens, Double temperature) {
    return chatOptions(model, maxCompletionTokens, temperature, null);
  }

  /**
   * reasoningEffort 까지 받는 오버로드(채팅 봇용). null·공백이면 넣지 않는다 — Responses 모델은 미전송 = OpenAI 서버 기본값.
   * ChatClient 의 defaultOptions 병합(combineWith)은 non-null 값만 덮으므로 여기서 안 넣은 값은 모델 기본 옵션이 산다.
   */
  static OpenAiChatOptions.Builder chatOptions(String model, int maxCompletionTokens, Double temperature, String reasoningEffort) {
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
        .model(model)
        .maxCompletionTokens(maxCompletionTokens);
    if (temperature != null) {
      builder.temperature(temperature);
    }
    if (StringUtils.isNotBlank(reasoningEffort)) {
      builder.reasoningEffort(reasoningEffort);
    }
    return builder;
  }
}

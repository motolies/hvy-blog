package kr.hvy.blog.infra.config;

import com.anthropic.client.AnthropicClient;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatClient;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.service.AdvisorNotifier;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesChatModel;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeMessagesClient;
import kr.hvy.blog.modules.advisor.client.claude.ClaudeModelSettings;
import kr.hvy.blog.modules.advisor.client.claude.RestClientHttpClient;
import kr.hvy.blog.modules.advisor.client.llm.FallbackChatModel;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderGate;
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
 * advisor 모듈의 LLM 조립. 공급자는 둘이다 — <b>Claude(Messages API)가 1순위, OpenAI(Responses API)가 폴백</b>(2026-11-11).
 * 역할(judge 판단 / assist 보조 / chat Slack 채팅 봇)마다 "Claude 모델 + OpenAI 모델 + 둘을 감싼 {@link FallbackChatModel}" 한 벌을 두고,
 * ChatClient 3종과 MarketJudgeClient 2종은 래퍼 위에 올린다. 호출부는 공급자를 모른다.
 * <ul>
 *   <li>폴백하는 경우: Claude 의 소진(크레딧·지출 한도)·장애(재시도 후 429·5xx·529, 타임아웃, 401·403·404)·거부(refusal).
 *       요청 오류(400)·출력 절단은 폴백하지 않고 실패한다 — 다른 공급자가 대신 답하면 버그가 가려진다</li>
 *   <li>준비 여부는 호출마다 읽는다({@code AdvisorProperties.claudeReady/openAiReady}): ANTHROPIC_API_KEY 가 비었거나
 *       {@code advisor.claude.enabled=false} 면 OpenAI 로만 부르고, OpenAI 키가 비면 Claude 로만 부른다</li>
 *   <li>Claude 의 차단 상태는 {@link LlmProviderGate} 하나를 세 역할이 공유한다 — 소진·키 오류·일시 장애는 계정 단위라 세 역할 모두,
 *       모델 없음(404)은 그 모델 ID 를 쓰는 역할만, 채팅의 응답 없음(짧은 타임아웃)은 채팅만 차단한다</li>
 *   <li>채팅은 Claude 클라이언트를 따로 쓴다({@link #CHAT_CLAUDE_CLIENT}) — 응답 타임아웃이 짧고 재시도가 1회 이하다. 채팅의 도구 마감 안에
 *       Claude 타임아웃과 OpenAI 폴백이 함께 들어와야 하기 때문이다</li>
 *   <li>두 공급자 모두 호출 1건이 tb_api_log 1행이다 — Claude 는 공식 SDK 타입을 쓰되 전송을 {@code claudeApiRestClient} 로 보낸다
 *       ({@link RestClientHttpClient}). 인증 헤더는 어느 쪽도 api_log 에 남지 않는다</li>
 * </ul>
 * 아래는 OpenAI 쪽 조립의 배경이다 — Responses API 클라이언트 1개(공유) + 역할별 ChatModel 3개. OpenAI 공식 SDK·Spring AI OpenAI 모듈은 쓰지 않는다 — 모든 호출이 {@code openAiRestClient} 를 지나
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
  /** Claude Messages 클라이언트 빈 이름 — 판단·보조용(응답 타임아웃 advisor.claude.timeout-seconds) */
  public static final String CLAUDE_CLIENT = "claudeMessagesClient";
  /** Claude Messages 클라이언트 빈 이름 — Slack 채팅 봇용(응답 타임아웃 advisor.claude.chat-timeout-seconds, 재시도 1회 이하) */
  public static final String CHAT_CLAUDE_CLIENT = "chatClaudeMessagesClient";
  /** 채팅 래퍼의 응답 없음(타임아웃·연결 실패·응답 해석 실패)을 한정하는 게이트 범위 이름 — 알림 문구에 그대로 실린다 */
  public static final String CHAT_UNANSWERED_SCOPE = "채팅 봇";
  /** 채팅용 Claude 클라이언트의 재시도 상한 — 최악이 "30초 이하 실패 + Retry-After 30초 + 타임아웃" 으로 묶여 채팅의 도구 마감 안에 든다 */
  static final int CHAT_CLAUDE_MAX_RETRIES = 1;
  /** 역할별 Claude ChatModel 빈 이름(1순위 공급자) */
  public static final String JUDGE_CLAUDE_MODEL = "judgeClaudeChatModel";
  public static final String ASSIST_CLAUDE_MODEL = "assistClaudeChatModel";
  public static final String CHAT_CLAUDE_MODEL = "chatClaudeChatModel";
  /** 역할별 폴백 래퍼 빈 이름 — ChatClient 가 올라가는 모델 */
  public static final String JUDGE_FALLBACK_MODEL = "judgeFallbackChatModel";
  public static final String ASSIST_FALLBACK_MODEL = "assistFallbackChatModel";
  public static final String CHAT_FALLBACK_MODEL = "chatFallbackChatModel";

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
   * Anthropic SDK 클라이언트(싱글턴). 전송은 claudeApiRestClient(→ tb_api_log)이고 SDK 에는 자격 증명을 주지 않는다 — 키는 RestClient 인터셉터가
   * 호출 시점에 넣으므로 ANTHROPIC_API_KEY 가 비어 있어도 기동된다. SDK 재시도는 꺼져 있다(재시도는 {@link ClaudeMessagesClient}).
   */
  @Bean
  public AnthropicClient anthropicClient(@Qualifier("claudeApiRestClient") RestClient claudeApiRestClient) {
    return RestClientHttpClient.anthropicClient(claudeApiRestClient);
  }

  /**
   * Claude Messages 클라이언트(판단·보조 모델 공유) — 일시 장애만 advisor.claude.max-retries 만큼 재시도하고 실패를 분류해 던진다.
   */
  @Bean(CLAUDE_CLIENT)
  public ClaudeMessagesClient claudeMessagesClient(AnthropicClient anthropicClient, AdvisorProperties properties) {
    return new ClaudeMessagesClient(anthropicClient, properties.getClaude().getMaxRetries());
  }

  /**
   * Slack 채팅 봇용 Claude Messages 클라이언트. 전송이 claudeChatApiRestClient(응답 타임아웃 advisor.claude.chat-timeout-seconds)이고 재시도는
   * {@value #CHAT_CLAUDE_MAX_RETRIES}회 이하다 — 채팅은 질문을 받을 때 도구 마감을 고정하므로, 판단용 클라이언트(300초·재시도 2회)를 쓰면 Claude 가
   * 실패로 끝났을 때 OpenAI 폴백이 도구를 부를 시간이 남지 않는다. SDK 클라이언트는 상태가 없는 얇은 래퍼라 빈으로 올리지 않고 여기서 만든다.
   */
  @Bean(CHAT_CLAUDE_CLIENT)
  public ClaudeMessagesClient chatClaudeMessagesClient(@Qualifier("claudeChatApiRestClient") RestClient claudeChatApiRestClient,
      AdvisorProperties properties) {
    return new ClaudeMessagesClient(RestClientHttpClient.anthropicClient(claudeChatApiRestClient),
        Math.min(properties.getClaude().getMaxRetries(), CHAT_CLAUDE_MAX_RETRIES));
  }

  /**
   * 판단용 Claude 모델. 도구가 없어 자동 캐싱·강제 마무리는 끈다.
   */
  @Bean(JUDGE_CLAUDE_MODEL)
  public ClaudeMessagesChatModel judgeClaudeChatModel(@Qualifier(CLAUDE_CLIENT) ClaudeMessagesClient client, AdvisorProperties properties) {
    AdvisorProperties.Claude claude = properties.getClaude();
    return new ClaudeMessagesChatModel(client, new ClaudeModelSettings(claude.getJudge(), claude.getJudgeMaxTokens(), claude.getJudgeEffort(), false, 0));
  }

  /**
   * 보조용 Claude 모델(교훈·회고·재현성 측정).
   */
  @Bean(ASSIST_CLAUDE_MODEL)
  public ClaudeMessagesChatModel assistClaudeChatModel(@Qualifier(CLAUDE_CLIENT) ClaudeMessagesClient client, AdvisorProperties properties) {
    AdvisorProperties.Claude claude = properties.getClaude();
    return new ClaudeMessagesChatModel(client, new ClaudeModelSettings(claude.getAssist(), claude.getAssistMaxTokens(), claude.getAssistEffort(), false, 0));
  }

  /**
   * Slack 채팅 봇용 Claude 모델. 모델은 advisor.claude.chat(비면 assist). 도구 루프가 라운드마다 전체 입력을 재전송하므로 자동 프롬프트 캐싱을 켜고,
   * 도구 결과가 max-total-tool-calls 이상 쌓이면 tool_choice=none 으로 답을 강제한다(OpenAI 채팅 모델과 같은 상한).
   * 클라이언트는 반드시 채팅용({@link #CHAT_CLAUDE_CLIENT})을 @Qualifier 로 받는다 — 판단용이 꽂히면 타임아웃이 300초가 된다.
   */
  @Bean(CHAT_CLAUDE_MODEL)
  public ClaudeMessagesChatModel chatClaudeChatModel(@Qualifier(CHAT_CLAUDE_CLIENT) ClaudeMessagesClient client, AdvisorProperties properties,
      AdvisorChatProperties chat) {
    AdvisorProperties.Claude claude = properties.getClaude();
    return new ClaudeMessagesChatModel(client,
        new ClaudeModelSettings(claude.chatOrAssist(), claude.getChatMaxTokens(), claude.getChatEffort(), true, Math.max(1, chat.getMaxTotalToolCalls())));
  }

  /**
   * Claude 차단 게이트(3역할 공유). 차단 진입 알림은 AdvisorNotifier 로 — 키·모델 오류는 #hvy-error(멘션), 소진·장애는 #hvy-notify, 분류별 하루 1회.
   * AdvisorNotifier.alert 가 전달 여부를 돌려주므로 Notify 빈이 없거나 전송이 예외로 끝난 날은 다음 실패 때 다시 알린다
   * (Slack 호출의 예외와 ok=false 응답은 hvy-common SlackClient 에서 전송 성공으로 보인다 — 그 경우는 재시도되지 않는다).
   * 차단 시간이 음수로 설정돼도 기동은 되게 0 으로 묶는다(0 = 차단 없이 알림만).
   */
  @Bean
  public LlmProviderGate claudeGate(AdvisorProperties properties, AdvisorNotifier notifier) {
    AdvisorProperties.ClaudeCooldown cooldown = properties.getClaude().getCooldown();
    return new LlmProviderGate(Clock.systemUTC(),
        Duration.ofMinutes(Math.max(0, cooldown.getExhaustedMinutes())),
        Duration.ofMinutes(Math.max(0, cooldown.getUnavailableMinutes())),
        Duration.ofMinutes(Math.max(0, cooldown.getMisconfiguredMinutes())),
        notifier::alert);
  }

  /**
   * 판단용 폴백 래퍼 — Claude judge 모델이 1순위, OpenAI judge 모델이 폴백. 준비 여부는 호출마다 읽는다.
   */
  @Bean(JUDGE_FALLBACK_MODEL)
  public FallbackChatModel judgeFallbackChatModel(@Qualifier(JUDGE_CLAUDE_MODEL) ClaudeMessagesChatModel claudeModel,
      @Qualifier(JUDGE_MODEL) OpenAiResponsesChatModel openAiModel, LlmProviderGate claudeGate, AdvisorProperties properties) {
    return new FallbackChatModel(LlmProvider.CLAUDE, properties.getClaude().getJudge(), claudeModel,
        () -> properties.claudeReady(properties.getClaude().getJudge()),
        openAiModel, () -> properties.openAiReady(properties.getModel().getJudge()), claudeGate);
  }

  /**
   * 보조용 폴백 래퍼.
   */
  @Bean(ASSIST_FALLBACK_MODEL)
  public FallbackChatModel assistFallbackChatModel(@Qualifier(ASSIST_CLAUDE_MODEL) ClaudeMessagesChatModel claudeModel,
      @Qualifier(ASSIST_MODEL) OpenAiResponsesChatModel openAiModel, LlmProviderGate claudeGate, AdvisorProperties properties) {
    return new FallbackChatModel(LlmProvider.CLAUDE, properties.getClaude().getAssist(), claudeModel,
        () -> properties.claudeReady(properties.getClaude().getAssist()),
        openAiModel, () -> properties.openAiReady(properties.getModel().getAssist()), claudeGate);
  }

  /**
   * 채팅용 폴백 래퍼. 한 턴(질문 1건의 도구 루프)이 한 번 OpenAI 로 넘어가면 그 턴은 끝까지 OpenAI 가 맡는다(FallbackChatModel 의 STICKY).
   * 채팅은 응답 타임아웃이 판단보다 짧아서(90초 vs 300초) 채팅이 겪은 응답 없음은 채팅 범위로만 차단한다 — 계정 단위로 두면 느린 채팅 한 번이
   * 5분간 판단·보조 호출까지 OpenAI 로 내려보낸다.
   */
  @Bean(CHAT_FALLBACK_MODEL)
  public FallbackChatModel chatFallbackChatModel(@Qualifier(CHAT_CLAUDE_MODEL) ClaudeMessagesChatModel claudeModel,
      @Qualifier(CHAT_MODEL) OpenAiResponsesChatModel openAiModel, LlmProviderGate claudeGate, AdvisorProperties properties,
      AdvisorChatProperties chat) {
    return new FallbackChatModel(LlmProvider.CLAUDE, properties.getClaude().chatOrAssist(), CHAT_UNANSWERED_SCOPE, claudeModel,
        () -> properties.claudeReady(properties.getClaude().chatOrAssist()),
        openAiModel, () -> properties.openAiReady(StringUtils.defaultIfBlank(chat.getModel(), properties.getModel().getAssist())), claudeGate);
  }

  /**
   * 판단용 ChatClient — 폴백 래퍼 위에 올린다. 옵션은 공급자 모델 것이 base 라 defaultOptions 를 두지 않는다(래퍼의 기본 옵션은 비어 있다).
   * 관측 레지스트리는 의도적으로 기본(NOOP) — HTTP 관측은 RestClient 계층이 한다.
   */
  @Bean(JUDGE)
  public ChatClient judgeChatClient(@Qualifier(JUDGE_FALLBACK_MODEL) FallbackChatModel judgeModel) {
    return ChatClient.builder(judgeModel).build();
  }

  /**
   * 보조용 ChatClient (채점 요약·교훈 후보 생성·재현성 측정).
   */
  @Bean(ASSIST)
  public ChatClient assistChatClient(@Qualifier(ASSIST_FALLBACK_MODEL) FallbackChatModel assistModel) {
    return ChatClient.builder(assistModel).build();
  }

  /**
   * Slack 채팅 봇용 ChatClient (chat-v1, 2026-09-13) — 자유 텍스트 + 스레드 히스토리 + 도구 루프. 폴백 래퍼 위에 올린다. 모델 파라미터는 반드시
   * @Qualifier 로 받는다(역할별 3빈이라 파라미터 이름 폴백에 기대면 이름이 바뀔 때 judge 모델이 채팅에 꽂힌다).
   * <p>
   * Spring AI 2.0 에는 반복 횟수 옵션이 없고 ToolCallingManager 의 도구별·총합 호출 상한(기본 40/150 은 Slack 질문 하나에 과함)으로 건다.
   * 상한 초과는 RETURN_ERROR_RESPONSE — THROW 면 예외가 되어 사용자가 아무 답도 못 받지만, 오류 ToolResponse 로 돌려주면 모델이 "지금까지 얻은 값으로
   * 답한다" 로 마무리한다. DefaultChatClient 가 ToolCallingAdvisor 를 자동 등록하므로 커스텀 매니저는 5-인자 builder 오버로드로 넘긴다(중복 advisor 금지).
   */
  @Bean(AdvisorChatClient.CHAT_BEAN)
  public ChatClient chatChatClient(@Qualifier(CHAT_FALLBACK_MODEL) FallbackChatModel chatModel, AdvisorChatProperties chat,
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

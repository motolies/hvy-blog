package kr.hvy.blog.modules.advisor.application.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.tool.AdviceToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.CalendarToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.ChatRequestScope;
import kr.hvy.blog.modules.advisor.application.chat.tool.MarketToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.StockToolkit;
import kr.hvy.blog.modules.advisor.application.service.MarketJudgeClient;
import kr.hvy.blog.modules.advisor.application.service.PromptResources;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.llm.LlmTokenUsage;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 채팅 답변자 — 정적 시스템 프롬프트 + 스레드 히스토리 + 질문을 도구 14종과 함께 ChatClient 에 보내고 결과·사용량·도구 호출 목록을 {@link ChatResult} 로 돌려준다.
 * <p>
 * {@code MarketJudgeClient} 를 재사용하지 않는 이유: 그쪽은 strict JSON 스키마 단발 호출 계약이고 여기는 자유 텍스트 + 대화 + 도구 루프다.
 * ChatClient 는 채팅용 폴백 래퍼(AdvisorAiConfig.CHAT_FALLBACK_MODEL) 위에 있다 — Claude 채팅 모델이 1순위이고 소진·장애·거부일 때 OpenAI 채팅 모델로
 * 넘어가며, 한 턴이 한 번 넘어가면 그 턴의 도구 루프는 끝까지 OpenAI 가 맡는다(2026-11-11). 모델·도구 상한·effort 는 공급자별 채팅 모델이 갖고 있고,
 * 이 클래스는 {@code .options()} 를 부르지 않으므로 그 값이 그대로 요청에 실린다.
 * <p>
 * 추론·캐시 토큰: OpenAI 응답은 Responses 모델이 메타데이터로 나른 턴 누적값을 쓴다. Claude 응답은 캐시 토큰을 Spring AI 표준 슬롯의 라운드 합산으로 얻고,
 * 추론 토큰은 단일 라운드일 때만 기록된다(여러 라운드면 0 — native usage 가 합산 시 버려진다).
 * 사용자 메시지는 {@code .user(String)} 으로 넣는다 — 템플릿 렌더러가 {@code {}} 를 변수로 해석하므로 질문에 중괄호가 있어도 터지지 않게(AdvicePromptBuilder 와 같은 이유).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorChatClient implements ChatAnswerer {

  /** AdvisorAiConfig 가 등록하는 채팅용 ChatClient 빈 이름(judge/assist 와 같은 방향으로 상수는 모듈 쪽에 둔다) */
  public static final String CHAT_BEAN = "chatChatClient";
  static final String EMPTY_ANSWER = "답변을 만들지 못했습니다(모델 응답이 비어 있음). 질문을 조금 바꿔 다시 물어봐 주세요.";

  private final ChatClient chatClient;
  private final PromptResources promptResources;
  private final AdvisorChatProperties properties;
  private final AdvisorProperties advisorProperties;
  private final SlackThreadHistory threadHistory;
  private final MarketToolkit marketToolkit;
  private final StockToolkit stockToolkit;
  private final AdviceToolkit adviceToolkit;
  private final CalendarToolkit calendarToolkit;

  public AdvisorChatClient(@Qualifier(CHAT_BEAN) ChatClient chatClient, PromptResources promptResources, AdvisorChatProperties properties,
      AdvisorProperties advisorProperties, SlackThreadHistory threadHistory, MarketToolkit marketToolkit, StockToolkit stockToolkit,
      AdviceToolkit adviceToolkit, CalendarToolkit calendarToolkit) {
    this.chatClient = chatClient;
    this.promptResources = promptResources;
    this.properties = properties;
    this.advisorProperties = advisorProperties;
    this.threadHistory = threadHistory;
    this.marketToolkit = marketToolkit;
    this.stockToolkit = stockToolkit;
    this.adviceToolkit = adviceToolkit;
    this.calendarToolkit = calendarToolkit;
  }

  @Override
  public ChatResult answer(IncomingQuestion question) {
    SlackThreadHistory.History history = threadHistory.load(question, null);
    ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(Math.max(10, properties.getAnswerTimeoutSeconds())), question.userId());
    long started = System.currentTimeMillis();
    ChatResponse response = chatClient.prompt()
        .system(promptResources.chatSystem())
        .messages(history.messages())
        .user(userText(question, history))
        .tools(marketToolkit, stockToolkit, adviceToolkit, calendarToolkit)
        .toolContext(scope.toToolContext())
        .call()
        .chatResponse();

    String text = response == null || response.getResult() == null || response.getResult().getOutput() == null ? null
        : response.getResult().getOutput().getText();
    if (text == null || text.isBlank()) {
      log.warn("advisor chat 모델 응답 비어 있음: tools={}", scope.calls());
      text = EMPTY_ANSWER;
    }
    ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
    Usage usage = metadata == null ? null : metadata.getUsage();
    int prompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    int completion = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank() ? resolvedModel() : metadata.getModel();
    // 도구 루프가 여러 라운드면 Spring AI 의 usage 합산이 native usage 를 버리므로, Responses 모델이 메타데이터로 나른 이 턴 누적값을 우선 쓴다
    Optional<LlmTokenUsage> responsesUsage = OpenAiResponsesChatModel.cumulativeUsage(response);
    int reasoning = responsesUsage.map(LlmTokenUsage::reasoningTokens).orElseGet(() -> MarketJudgeClient.reasoningTokens(usage));
    int cached = responsesUsage.map(LlmTokenUsage::cachedTokens).orElseGet(() -> MarketJudgeClient.cachedTokens(usage));
    List<String> calls = scope.calls();
    // 공급자는 마지막 라운드 기준이다. 턴 도중 폴백하면(STICKY) 앞 라운드의 Claude 사용량도 폴백 공급자 단가로 계산되는 근사다
    LlmProvider provider = LlmResponses.provider(response);
    log.info("advisor chat LLM: provider={}, fallback={}, model={}, history={}, tools={}, in={}, out={}, reasoning={}, cached={}, {}ms", provider,
        LlmResponses.fallbackReason(response), model, history.messages().size(), calls, prompt, completion, reasoning, cached,
        System.currentTimeMillis() - started);
    return ChatResult.builder()
        .answer(text)
        .model(model)
        .promptVersion(PromptResources.CHAT_VERSION)
        .historyMessages(history.messages().size())
        .toolCalls(calls)
        .promptTokens(prompt)
        .completionTokens(completion)
        .reasoningTokens(reasoning)
        .cachedTokens(cached)
        .costUsd(advisorProperties.costUsd(provider, prompt, completion, cached))
        .dataAsOf(scope.earliestAsOf().orElse(null))
        .build();
  }

  /**
   * 질문 본문. 루트가 봇의 일일 판단이면 기준일 힌트를 한 줄 덧붙여 latestAdvice(baseDate) 로 바로 가게 한다.
   */
  static String userText(IncomingQuestion question, SlackThreadHistory.History history) {
    if (history.adviceBaseDate() == null) {
      return question.text();
    }
    return question.text() + "\n\n(참고: 이 스레드의 루트 메시지는 " + history.adviceBaseDate() + " 기준 일일 판단이다)";
  }

  /**
   * 응답 메타데이터에 모델 ID 가 없을 때 쓰는 값(OpenAI 채팅 모델 설정). 공급자 모델은 둘 다 응답의 모델 ID 를 싣기 때문에 스텁 응답에서만 쓰인다.
   */
  private String resolvedModel() {
    return properties.getModel() == null || properties.getModel().isBlank() ? advisorProperties.getModel().getAssist() : properties.getModel();
  }
}

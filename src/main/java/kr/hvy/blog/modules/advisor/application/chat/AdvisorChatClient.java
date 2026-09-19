package kr.hvy.blog.modules.advisor.application.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesUsage;
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
 * ChatModel 도 다르다 — 채팅은 Responses API 모델({@code OpenAiResponsesChatModel}, 2026-09-19) 이고 judge/assist 는 Chat Completions 모델이다.
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
    ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(Math.max(10, properties.getAnswerTimeoutSeconds())));
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
    Optional<ResponsesUsage> responsesUsage = OpenAiResponsesChatModel.cumulativeUsage(response);
    int reasoning = responsesUsage.map(ResponsesUsage::reasoningTokens).orElseGet(() -> MarketJudgeClient.reasoningTokens(usage));
    int cached = responsesUsage.map(ResponsesUsage::cachedTokens).orElseGet(() -> MarketJudgeClient.cachedTokens(usage));
    List<String> calls = scope.calls();
    log.info("advisor chat LLM: model={}, history={}, tools={}, in={}, out={}, reasoning={}, cached={}, {}ms", model, history.messages().size(), calls,
        prompt, completion, reasoning, cached, System.currentTimeMillis() - started);
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
        .costUsd(cost(prompt, completion))
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

  private String resolvedModel() {
    return properties.getModel() == null || properties.getModel().isBlank() ? advisorProperties.getModel().getAssist() : properties.getModel();
  }

  /**
   * advisor.cost 단가(100만 토큰당 USD)로 비용을 계산한다. 단가가 0 이면 0.
   */
  BigDecimal cost(int promptTokens, int completionTokens) {
    AdvisorProperties.Cost cost = advisorProperties.getCost();
    if (cost == null || (cost.getInputPer1mUsd().signum() == 0 && cost.getOutputPer1mUsd().signum() == 0)) {
      return BigDecimal.ZERO;
    }
    BigDecimal million = BigDecimal.valueOf(1_000_000);
    return cost.getInputPer1mUsd().multiply(BigDecimal.valueOf(promptTokens))
        .add(cost.getOutputPer1mUsd().multiply(BigDecimal.valueOf(completionTokens)))
        .divide(million, 6, RoundingMode.HALF_UP);
  }
}

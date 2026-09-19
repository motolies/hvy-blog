package kr.hvy.blog.modules.advisor.application.chat;

import static kr.hvy.blog.modules.advisor.AdvisorSyntheticData.seedStockData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.tool.AdviceToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.CalendarToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.MarketToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.StockToolkit;
import kr.hvy.blog.modules.advisor.application.chat.tool.ToolSupport;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.CandidateScreeningService;
import kr.hvy.blog.modules.advisor.application.service.GlobalLinkService;
import kr.hvy.blog.modules.advisor.application.service.MarketFeatureService;
import kr.hvy.blog.modules.advisor.application.service.MarketTrendService;
import kr.hvy.blog.modules.advisor.application.service.PromptResources;
import kr.hvy.blog.modules.advisor.application.service.TradingCalendar;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiBearerAuthInterceptor;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실계정 수동 실측 — 실제 OpenAI 모델이 Responses API(/v1/responses, 2026-09-19)로 도구 14종을 골라 부르고(ToolContext 주입·호출 상한·오류 JSON 포함)
 * 한국어 답을 내는지 본다. 데이터는 PG 컨테이너의 합성 세트(종목0~종목39, T00~T39)라 답의 내용보다 <b>도구 선택·기준일 명시·날조 거부</b>를 눈으로 확인한다.
 * HTTP 는 운영과 달리 api_log 인터셉터 없는 맨 RestClient(인증 인터셉터만)라 DB 없이 돈다.
 * <pre>OPENAI_API_KEY=... ADVISOR_CHAT_MODEL=... DOCKER_HOST=... ./gradlew test --tests "kr.hvy.blog.modules.advisor.application.chat.AdvisorChatManualTest"</pre>
 */
@Testcontainers
class AdvisorChatManualTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  @BeforeAll
  static void schemaAndData() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-seed.sql"));
    }
    seedStockData(new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())));
  }

  @Test
  @DisplayName("실제 모델: 질문 4개(종목 흐름·상위 N·추세·날조 유도)에 도구를 골라 부르고 기준일이 찍힌 답을 낸다")
  void realModelUsesTools() {
    String apiKey = System.getenv("OPENAI_API_KEY");
    String model = System.getenv().getOrDefault("ADVISOR_CHAT_MODEL", System.getenv("ADVISOR_ASSIST_MODEL"));
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 없음 — 수동 실측 스킵");
    Assumptions.assumeTrue(model != null && !model.isBlank(), "ADVISOR_CHAT_MODEL/ADVISOR_ASSIST_MODEL 없음 — 수동 실측 스킵");

    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
    AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
    properties.setMarkets(List.of("KOSPI", "KOSDAQ"));
    properties.getModel().setAssist(model);
    AdvisorChatProperties chat = new AdvisorChatProperties(new MockEnvironment(), properties);
    MarketCalendarService calendarService = mock(MarketCalendarService.class);
    when(calendarService.isTradingDay(any())).thenAnswer(inv -> {
      DayOfWeek day = ((LocalDate) inv.getArgument(0)).getDayOfWeek();
      return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    });
    StockLookupReader reader = new StockLookupReader(jdbc);
    ToolSupport support = new ToolSupport(new DataSourceTransactionManager(ds), jdbc, chat, reader);
    MarketTrendService trends = new MarketTrendService(named, properties);
    GlobalLinkService links = new GlobalLinkService(named, properties);
    TradingCalendar tradingCalendar = new TradingCalendar(calendarService);
    MarketFeatureService features = new MarketFeatureService(named, properties, trends, tradingCalendar, links);
    WeightSetRepository weightSets = new WeightSetRepository(jdbc);
    AdviceWriter adviceWriter = new AdviceWriter(jdbc);
    MarketToolkit market = new MarketToolkit(support, features, trends, links, properties);
    StockToolkit stock = new StockToolkit(support, reader, new DerivedViewRefresher(jdbc), new StockNewsWriter(new BatchUpsertSupport(jdbc), jdbc));
    AdviceToolkit advice = new AdviceToolkit(support, adviceWriter, new ScoreWriter(new BatchUpsertSupport(jdbc), jdbc), new MorningCheckWriter(jdbc),
        new IntradayCheckWriter(jdbc), new CandidateScreeningService(named, weightSets, properties), weightSets, new AdvisorKpiService(named, properties), properties);
    CalendarToolkit calendar = new CalendarToolkit(support, reader, features, adviceWriter, tradingCalendar);

    // 운영 AdvisorAiConfig.chatResponsesChatModel + chatChatClient 와 같은 조립 — RestClient 만 api_log 인터셉터 없이 인증 인터셉터만 단다(옵션은 모델에만)
    RestClient restClient = RestClient.builder().baseUrl("https://api.openai.com")
        .requestInterceptor(new OpenAiBearerAuthInterceptor(() -> apiKey)).build();
    ResponsesChatOptions defaults = ResponsesChatOptions.builder().model(model).maxTokens(chat.getMaxCompletionTokens()).build();
    OpenAiResponsesChatModel chatModel = new OpenAiResponsesChatModel(new OpenAiResponsesClient(restClient, 1), defaults, chat.getMaxTotalToolCalls());
    ToolCallingManager manager = ToolCallingManager.builder().maxCallsPerTool(chat.getMaxCallsPerTool()).maxTotalToolCalls(chat.getMaxTotalToolCalls())
        .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE).build();
    ChatClient chatClient = ChatClient.builder(chatModel, io.micrometer.observation.ObservationRegistry.NOOP, null, null,
            ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true))
        .build();
    SlackChatGateway noSlack = mock(SlackChatGateway.class);
    AdvisorChatClient answerer = new AdvisorChatClient(chatClient, new PromptResources(), chat, properties, new SlackThreadHistory(noSlack, chat),
        market, stock, advice, calendar);

    String[] questions = {"종목5 최근 흐름 어때?", "20일 수익률 상위 5개 알려줘", "코스피 지금 강세장이야?", "종목5 올해 영업이익 얼마야?"};
    for (String q : questions) {
      ChatResult r = answerer.answer(new IncomingQuestion("Ev", "C1", "U1", null, "1.0", null, q));
      System.out.println("=== Q: " + q + "\n--- tools: " + r.toolCalls() + " · in " + r.promptTokens() + " / out " + r.completionTokens()
          + " (reasoning " + r.reasoningTokens() + ", cached " + r.cachedTokens() + ") · asOf " + r.dataAsOf() + "\n" + r.answer());
      assertThat(r.answer()).isNotBlank();
    }
  }
}

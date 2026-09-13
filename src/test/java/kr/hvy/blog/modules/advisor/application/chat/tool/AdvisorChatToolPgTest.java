package kr.hvy.blog.modules.advisor.application.chat.tool;

import static kr.hvy.blog.modules.advisor.AdvisorSyntheticData.BASE;
import static kr.hvy.blog.modules.advisor.AdvisorSyntheticData.DATES;
import static kr.hvy.blog.modules.advisor.AdvisorSyntheticData.seedStockData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.CandidateScreeningService;
import kr.hvy.blog.modules.advisor.application.service.GlobalLinkService;
import kr.hvy.blog.modules.advisor.application.service.MarketFeatureService;
import kr.hvy.blog.modules.advisor.application.service.MarketTrendService;
import kr.hvy.blog.modules.advisor.application.service.TradingCalendar;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 채팅 봇 도구 14종을 실제 PostgreSQL + 합성 데이터(40종목 × 30거래일)로 검증한다 — 반환 형태·asOf 존재·룩어헤드 불변식(미래 기준일 → 마지막 거래일 클램프)·
 * 상한·데이터 없음의 오류 형태·요청 범위(호출 이름·기준일) 기록. Docker 소켓은 colima 사용 시 DOCKER_HOST.
 */
@Testcontainers
class AdvisorChatToolPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  /** 합성 데이터 마지막 날 + 30일 — 어떤 도구도 이 날짜의 행을 만들거나 새어 나가게 하면 안 된다 */
  static final LocalDate FUTURE = BASE.plusDays(30);

  private JdbcTemplate jdbc;
  private ChatRequestScope scope;
  private ToolContext context;
  private MarketToolkit market;
  private StockToolkit stock;
  private AdviceToolkit advice;
  private CalendarToolkit calendar;

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

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
    AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
    properties.setMarkets(List.of("KOSPI", "KOSDAQ"));
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
    market = new MarketToolkit(support, features, trends, links, properties);
    stock = new StockToolkit(support, reader, new DerivedViewRefresher(jdbc), new StockNewsWriter(new BatchUpsertSupport(jdbc), jdbc));
    advice = new AdviceToolkit(support, adviceWriter, new ScoreWriter(new BatchUpsertSupport(jdbc), jdbc), new MorningCheckWriter(jdbc),
        new IntradayCheckWriter(jdbc), new CandidateScreeningService(named, weightSets, properties), weightSets, new AdvisorKpiService(named, properties), properties);
    calendar = new CalendarToolkit(support, reader, features, adviceWriter, tradingCalendar);
    scope = new ChatRequestScope(Instant.now().plusSeconds(60));
    context = new ToolContext(scope.toToolContext());
  }

  @Test
  @DisplayName("시장 개요·추세·연동 — 미래 기준일을 넣어도 마지막 거래일로 클램프되고 asOf 가 실린다")
  void marketToolsClampFuture() {
    Map<String, Object> overview = market.marketOverview(FUTURE.toString(), context);
    assertThat(overview).containsEntry("asOf", BASE.toString()).containsKeys("indices", "flows", "global", "sectorsTop", "trend", "dataAsOf", "window");
    assertThat(overview).doesNotContainKey("error");

    Map<String, Object> trend = market.marketTrend(FUTURE.toString(), context);
    assertThat(trend).containsEntry("asOf", BASE.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) trend.get("items");
    assertThat(items).extracting(i -> i.get("index")).containsExactlyInAnyOrder("0001", "1001");
    assertThat(items.getFirst()).containsKeys("code", "score", "components", "days");

    // 미국 데이터가 없는 합성 세트 — 예외가 아니라 no_data
    Map<String, Object> link = market.globalLink("KOSPI", "SPX", null, context);
    assertThat(link).containsEntry("error", ToolJson.ERROR_NO_DATA);

    assertThat(scope.calls()).containsExactly("marketOverview", "marketTrend", "globalLink");
    assertThat(scope.earliestAsOf()).contains(BASE);
  }

  @Test
  @DisplayName("종목 해석 — 코드·이름 일부로 찾고 모르는 이름은 no_data")
  void resolveStock() {
    Map<String, Object> byCode = stock.resolveStock("T05", context);
    assertThat(byCode).containsEntry("n", 1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) byCode.get("items");
    assertThat(items.getFirst()).containsEntry("tk", "T05").containsEntry("nm", "종목5").containsEntry("mkt", "KOSDAQ").containsEntry("grp", "ST").containsEntry("act", true);

    Map<String, Object> byName = stock.resolveStock("종목1", context);
    assertThat((Integer) byName.get("n")).isEqualTo(StockToolkit.RESOLVE_LIMIT);   // 종목1, 종목10~19 → 8행 상한
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> named = (List<Map<String, Object>>) byName.get("items");
    assertThat(named.getFirst()).containsEntry("nm", "종목1");   // 정확 일치 우선

    assertThat(stock.resolveStock("없는회사", context)).containsEntry("error", ToolJson.ERROR_NO_DATA);
    assertThat(stock.resolveStock("%_", context)).containsEntry("error", ToolJson.ERROR_NO_DATA);   // LIKE 와일드카드는 이스케이프
  }

  @Test
  @DisplayName("종목 스냅샷·시계열 — 수정 종가, 미래 기준일 클램프, 모르는 종목은 no_data")
  void snapshotAndSeries() {
    Map<String, Object> snap = stock.stockSnapshot("T10", FUTURE.toString(), context);
    assertThat(snap).containsEntry("tk", "T10").containsEntry("nm", "종목10").containsEntry("asOf", BASE.toString()).containsEntry("sector", "섹터2");
    assertThat(snap).containsEntry("close", 100L + 10L * (DATES.size() - 1));
    assertThat(snap).containsKey("r20").doesNotContainKey("per");   // 밸류 스냅샷 없음 → 키 생략

    Map<String, Object> series = stock.priceSeries("T10", FUTURE.toString(), 10, context);
    assertThat(series).containsEntry("n", 10).containsEntry("to", BASE.toString());
    @SuppressWarnings("unchecked")
    List<Long> closes = (List<Long>) series.get("c");
    assertThat(closes).hasSize(10).isSorted();
    assertThat(closes.getLast()).isEqualTo(100L + 10L * (DATES.size() - 1));

    Map<String, Object> all = stock.priceSeries("T10", null, 250, context);
    assertThat(all).containsEntry("n", DATES.size());

    assertThat(stock.stockSnapshot("ZZZ", null, context)).containsEntry("error", ToolJson.ERROR_NO_DATA);
    assertThat(stock.priceSeries("ZZZ", null, null, context)).containsEntry("error", ToolJson.ERROR_NO_DATA);
    assertThat(scope.earliestAsOf()).contains(BASE);
  }

  @Test
  @DisplayName("지표 상위 N — enum 밖 컬럼은 SQL 에 닿지 않고, 정렬·시장·상한이 지켜진다")
  void metricTopN() {
    assertThat(stock.metricTopN("close; DROP TABLE tb_stock_master", null, null, 5, null, context)).containsEntry("error", ToolJson.ERROR_BAD_ARGUMENT);
    assertThat(stock.metricTopN("ret_20d", null, "NYSE", 5, null, context)).containsEntry("error", ToolJson.ERROR_BAD_ARGUMENT);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_master", Long.class)).isEqualTo(40L);

    Map<String, Object> top = stock.metricTopN("ret_20d", "desc", "KOSPI", 50, FUTURE.toString(), context);
    assertThat(top).containsEntry("asOf", BASE.toString()).containsEntry("col", "ret_20d").containsEntry("market", "KOSPI").containsEntry("n", StockToolkit.TOPN_CAP);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) top.get("items");
    assertThat(items).allSatisfy(i -> assertThat(i).containsEntry("mkt", "KOSPI"));
    assertThat(items.getFirst()).containsEntry("tk", "T38");   // ret_20d = i/40 → KOSPI(짝수) 최대 38
    List<Double> values = items.stream().map(i -> (Double) i.get("v")).toList();
    assertThat(values).isSortedAccordingTo((a, b) -> Double.compare(b, a));

    Map<String, Object> asc = stock.metricTopN("RET_20D", "asc", null, 3, null, context);
    assertThat(asc).containsEntry("n", 3);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ascItems = (List<Map<String, Object>>) asc.get("items");
    assertThat(ascItems.getFirst()).containsEntry("tk", "T00");

    Map<String, Object> money = stock.metricTopN("foreign_net_5d", null, null, 2, null, context);
    assertThat(money).containsEntry("unit", "억원");
  }

  @Test
  @DisplayName("뉴스 제목 — 수집이 없으면 빈 목록과 안내(오류 아님)")
  void newsEmptyIsNormal() {
    Map<String, Object> news = stock.newsHeadlines(null, 48, context);
    assertThat(news).containsEntry("n", 0).containsEntry("hours", 48).containsKey("note").doesNotContainKey("error");
    assertThat(stock.newsHeadlines("T01", 500, context)).containsEntry("hours", StockToolkit.NEWS_MAX_HOURS);
  }

  @Test
  @DisplayName("판단 도구 — 판단이 없으면 no_data, 스크리닝은 시드 가중치로 동작하고 상한을 지킨다")
  void adviceTools() {
    assertThat(advice.latestAdvice(null, context)).containsEntry("error", ToolJson.ERROR_NO_DATA);
    assertThat(advice.adviceChecks(FUTURE.toString(), context)).containsEntry("error", ToolJson.ERROR_NO_DATA);

    Map<String, Object> screen = advice.screeningTop(FUTURE.toString(), 99, context);
    assertThat(screen).containsEntry("asOf", BASE.toString()).containsKeys("universe", "cut", "weightSetId");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) screen.get("items");
    assertThat(items).isNotEmpty().hasSizeLessThanOrEqualTo(AdviceToolkit.SCREEN_CAP);
    assertThat(items.getFirst()).containsKeys("rank", "tk", "nm", "score", "sig");

    Map<String, Object> perf = advice.performanceSummary(4, context);
    assertThat(perf).containsKeys("from", "to", "variants", "regime", "trendOutlook", "morning", "note");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> variants = (List<Map<String, Object>>) perf.get("variants");
    assertThat(variants).extracting(v -> v.get("variant")).contains("LIVE", "QUANT_TOPN");
    assertThat((String) perf.get("note")).contains("표본");
    assertThat(scope.calls()).containsExactly("latestAdvice", "adviceChecks", "screeningTop", "performanceSummary");
  }

  @Test
  @DisplayName("달력·신선도 — 마지막 거래일과 거래일 목록")
  void calendarTools() {
    Map<String, Object> fresh = calendar.dataFreshness(context);
    assertThat(fresh).containsEntry("lastTradingDayWithData", BASE.toString()).containsKeys("today", "dataAsOf", "schedule");

    Map<String, Object> next = calendar.tradingDays(BASE.toString(), null, 3, context);
    assertThat(next).containsEntry("n", 3);
    @SuppressWarnings("unchecked")
    List<String> days = (List<String>) next.get("days");
    assertThat(days).allSatisfy(d -> assertThat(LocalDate.parse(d)).isAfter(BASE));

    Map<String, Object> range = calendar.tradingDays(BASE.toString(), BASE.plusDays(7).toString(), null, context);
    assertThat((Integer) range.get("n")).isEqualTo(5);

    Map<String, Object> capped = calendar.tradingDays(null, null, 500, context);
    assertThat(capped).containsEntry("n", CalendarToolkit.MAX_DAYS);
  }

  @Test
  @DisplayName("마감을 넘긴 요청은 SQL 없이 deadline 오류를 돌려주고 호출은 기록된다")
  void deadlineExceeded() {
    ChatRequestScope expired = new ChatRequestScope(Instant.now().minusSeconds(1));
    ToolContext expiredContext = new ToolContext(expired.toToolContext());
    Map<String, Object> result = stock.resolveStock("T01", expiredContext);
    assertThat(result).containsEntry("error", ToolJson.ERROR_DEADLINE);
    assertThat(expired.calls()).containsExactly("resolveStock");
  }

  @Test
  @DisplayName("ToolContext 없이 직접 불러도(테스트·수동) 동작한다")
  void worksWithoutScope() {
    assertThat(stock.resolveStock("T02", null)).containsEntry("n", 1);
  }
}

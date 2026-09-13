package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 규칙 추세 라벨(TrendSql)의 PG 검증: 성분 점수, confirm-days 연속 확인(휩소 무시), 에피소드 since·days, 기저율, 그리고 같은 정의로 도는 채점(TREND·TREND_INV).
 * <p>
 * 지수는 1000 상수(MA·60일 성분 전부 0)로 두고 breadth 성분만 종목 지표로 조종한다. 임계를 ±1 로 낮춰 breadth 하나로 라벨이 정해지게 한다.
 * 80영업일: 0~39 breadth 0.8(BULL) · 40 단 하루 0.5(SIDEWAYS 휩소) · 41~59 0.8 · 60~79 0.2(BEAR).
 */
@Testcontainers
class MarketTrendPgTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final List<LocalDate> D = AdvisorSyntheticData.businessDays(LocalDate.of(2026, 3, 2), 80);
  static final int TICKERS = 10;

  private JdbcTemplate jdbc;
  private NamedParameterJdbcTemplate named;
  private AdvisorProperties properties;
  private MarketTrendService trends;
  private AdviceScoringService scoring;

  @BeforeAll
  static void schemaAndData() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-seed.sql"));
    }
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES ('0001', 'KOSPI'), ('1001', 'KOSDAQ')");
    for (LocalDate d : D) {
      jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) "
          + "VALUES ('0001', ?, 1000, 1000, 1000, 1000), ('1001', ?, 1000, 1000, 1000, 1000)", d, d);
    }
    for (int i = 0; i < TICKERS; i++) {
      String t = String.format("B%02d", i);
      jdbc.update("INSERT INTO tb_stock_master (ticker, stock_name, market_type, security_group, created_at, updated_at) VALUES (?, ?, 'KOSPI', 'ST', NOW(), NOW())",
          t, "종목" + i);
      for (int k = 0; k < D.size(); k++) {
        // breadth = MA20(100) 위 종목 비율: 0~39 0.8, 40 0.5, 41~59 0.8, 60~79 0.2
        double ratio = k == 40 ? 0.5 : k >= 60 ? 0.2 : 0.8;
        double close = i < Math.round(ratio * TICKERS) ? 110 : 90;
        jdbc.update("INSERT INTO tb_stock_daily_metric (ticker, trade_date, adj_close, ret_1d, ma_20) VALUES (?, ?, ?, 0, 100)", t, D.get(k), close);
      }
    }
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_market_breadth_daily");
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    named = new NamedParameterJdbcTemplate(ds);
    properties = new AdvisorProperties(new MockEnvironment());
    properties.getTrend().setBullThreshold(1);
    properties.getTrend().setBearThreshold(-1);
    trends = new MarketTrendService(named, properties);
    scoring = new AdviceScoringService(named, mock(ScoreWriter.class), properties, mock(kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter.class));
  }

  @Test
  @DisplayName("breadth 성분으로 BULL 이 확정되고, 하루짜리 휩소(40일차)는 확정 라벨을 바꾸지 않는다")
  void confirmsAndIgnoresWhipsaw() {
    MarketTrend day45 = trends.trends(D.get(45)).getFirst();
    assertThat(day45.indexCode()).isEqualTo("0001");
    assertThat(day45.code()).isEqualTo(MarketTrendCode.BULL);
    assertThat(day45.components()).containsEntry("breadth", 1).containsEntry("ma20", 0).containsEntry("ma60", 0).containsEntry("ma120", 0).containsEntry("ret60", 0);
    assertThat(day45.score()).isEqualTo(1);
    assertThat(day45.breadth()).isEqualTo(0.8);
    assertThat(day45.since()).as("첫날부터 한 에피소드 — 휩소가 끊지 않았다").isEqualTo(D.getFirst());
    assertThat(day45.days()).isEqualTo(46);

    MarketTrend day40 = trends.trends(D.get(40)).getFirst();
    assertThat(day40.rawCode()).isEqualTo(MarketTrendCode.SIDEWAYS);
    assertThat(day40.code()).as("raw 는 흔들려도 확정은 BULL").isEqualTo(MarketTrendCode.BULL);
  }

  @Test
  @DisplayName("BEAR 는 연속 2일째(61일차)에 확정되고 since·days·기저율이 그에 맞는다")
  void confirmsBearOnSecondDay() {
    MarketTrend day60 = trends.trends(D.get(60)).getFirst();
    assertThat(day60.rawCode()).isEqualTo(MarketTrendCode.BEAR);
    assertThat(day60.code()).isEqualTo(MarketTrendCode.BULL);

    MarketTrend day79 = trends.trends(D.get(79)).getFirst();
    assertThat(day79.code()).isEqualTo(MarketTrendCode.BEAR);
    assertThat(day79.since()).isEqualTo(D.get(61));
    assertThat(day79.days()).isEqualTo(19);
    assertThat(day79.base()).isNotNull();
    assertThat(day79.base().episodes()).as("완료된 BEAR 에피소드 없음(진행 중만)").isZero();
    assertThat(day79.base().medianDays()).isNull();
    assertThat(day79.base().fwd5().n()).as("BEAR 일 중 5일 뒤가 asOf 안에 있는 날: 61~74").isEqualTo(14);
    assertThat(day79.base().fwd5().mean()).isEqualTo(0.0);
    assertThat(day79.base().fwd5().pUp()).isEqualTo(0.0);

    MarketTrend kosdaq = trends.trends(D.get(79)).get(1);
    assertThat(kosdaq.indexCode()).isEqualTo("1001");
    assertThat(kosdaq.breadth()).as("KOSDAQ 종목 없음 → breadth NULL → 성분 0").isNull();
    assertThat(kosdaq.code()).isEqualTo(MarketTrendCode.SIDEWAYS);
  }

  @Test
  @DisplayName("채점은 같은 라벨 정의를 쓴다: 45일차 판단(BULL)은 16거래일 뒤 전환 → ABOUT_20D, 무효화 미발동은 놓침, 20일차 판단은 BEYOND_20D 적중")
  void scoresWithSameDefinition() {
    AdviceHeader onDay45 = header(D.get(45), MarketTrendCode.BULL, TrendHorizon.ABOUT_20D, InvalidationType.BELOW_MA20);
    Map<String, CallScoreRow> rows = byKey(scoring.trendScores(onDay45, 20, ScoreStage.PROVISIONAL));
    CallScoreRow trend = rows.get("TREND:0001");
    assertThat(trend.status()).isEqualTo(ScoreStatus.SCORED);
    assertThat(trend.actualDir()).isEqualTo("ABOUT_20D");
    assertThat(trend.hit()).isTrue();
    assertThat(trend.eventDate()).as("확정 BEAR 첫날").isEqualTo(D.get(61));
    CallScoreRow inv = rows.get("TREND_INV:0001");
    assertThat(inv.actualDir()).as("종가 == MA20 이라 하향 이탈 없음").isEqualTo("QUIET");
    assertThat(inv.hit()).as("전환은 있었는데 신호가 없었다 = 놓침").isFalse();
    assertThat(inv.eventDate()).isNull();

    AdviceHeader onDay20 = header(D.get(20), MarketTrendCode.BULL, TrendHorizon.BEYOND_20D, InvalidationType.NONE);
    Map<String, CallScoreRow> early = byKey(scoring.trendScores(onDay20, 20, ScoreStage.PROVISIONAL));
    assertThat(early.get("TREND:0001").actualDir()).as("21~40일차 창(휩소 포함)에 확정 전환 없음").isEqualTo("BEYOND_20D");
    assertThat(early.get("TREND:0001").hit()).isTrue();
    assertThat(early.get("TREND_INV:0001").status()).isEqualTo(ScoreStatus.MISSING);

    AdviceHeader tooLate = header(D.get(70), MarketTrendCode.BEAR, TrendHorizon.BEYOND_20D, InvalidationType.ABOVE_MA20);
    assertThat(byKey(scoring.trendScores(tooLate, 20, ScoreStage.PROVISIONAL)).get("TREND:0001").status()).as("창 미완성").isEqualTo(ScoreStatus.MISSING);
  }

  private static AdviceHeader header(LocalDate baseDate, MarketTrendCode kospi, TrendHorizon persist, InvalidationType invalidation) {
    return AdviceHeader.builder().adviceId(1L).runId(1L).baseDate(baseDate).adviceKind(AdviceHeader.KIND_DAILY).variant(AdviceVariant.LIVE).horizonDays(5)
        .trendKospi(kospi).outlooks(List.of(new TrendOutlook("0001", persist, 0.7, invalidation))).build();
  }

  private static Map<String, CallScoreRow> byKey(List<CallScoreRow> rows) {
    Map<String, CallScoreRow> map = new java.util.HashMap<>();
    rows.forEach(r -> map.put(r.subjectType() + ":" + r.subjectCode(), r));
    assertThat(rows).extracting(CallScoreRow::subjectType).contains(CallSubject.TREND, CallSubject.TREND_INV);
    return map;
  }
}

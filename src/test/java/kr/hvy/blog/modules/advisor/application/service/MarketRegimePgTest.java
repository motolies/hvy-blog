package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickUniverse;
import kr.hvy.blog.modules.advisor.domain.code.ThemeStrength;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 합성 국면(M6)의 PG 검증: 변동성 백분위의 룩어헤드 부재, 테마 집계의 PIT 구성(편입 전 종목 제외), 후보 theme 특징, regime_json 왕복.
 * <p>
 * 변동성은 전용 지수 9001 로 본다 — 300영업일 중 앞 280일은 ±0.5% 교대, 뒤 20일은 ±3% 교대라 VBASE(마지막 날) σ20 이 분포 최댓값이다
 * (백분위 = 279/279 = 1.0 → HIGH). 테마는 공용 합성 데이터(40종목) 위에 kospi200_sector 를 채운다: 코드 = 1 + i mod 3, T04 만 'X'.
 * KOSPI200 구성(이력)은 짝수 i 중 i mod 6 ≠ 4 라 테마 '2' 에는 구성종목이 없다 — 비구성 종목이 섞이면 '2' 가 나타난다.
 */
@Testcontainers
class MarketRegimePgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final LocalDate BASE = AdvisorSyntheticData.BASE;
  static final List<LocalDate> DATES = AdvisorSyntheticData.DATES;
  /** 변동성 전용 지수 9001 의 날짜 (2024-01-01 부터 300영업일) — VBASE 는 마지막 날 */
  static final List<LocalDate> VOL_DATES = AdvisorSyntheticData.businessDays(LocalDate.of(2024, 1, 1), 300);
  static final LocalDate VBASE = VOL_DATES.getLast();
  /** 테마 강약이 rs60 을 갖도록 0001 을 DATES 앞 65영업일 보충(2500 상수 → KOSPI 수익률 0) */
  static final List<LocalDate> EXTRA = AdvisorSyntheticData.businessDays(LocalDate.of(2026, 5, 4), 65);

  private JdbcTemplate jdbc;
  private NamedParameterJdbcTemplate named;
  private AdvisorProperties properties;
  private MarketRegimeService regimes;

  @BeforeAll
  static void schemaAndData() throws Exception {
    AdvisorSyntheticData.install(POSTGRES);
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES ('9001', 'VOL-TEST')");
    double close = 1000;
    for (int j = 0; j < VOL_DATES.size(); j++) {
      if (j > 0) {
        double move = j >= 280 ? 0.03 : 0.005;
        close *= j % 2 == 0 ? 1 + move : 1 - move;
      }
      insertIndex(jdbc, "9001", VOL_DATES.get(j), close);
    }
    for (LocalDate d : EXTRA) {
      insertIndex(jdbc, "0001", d, 2500);
    }
    // 테마 코드: 1 + i mod 3 (비구성 종목에도 채워 둔다 — PIT 구성 필터가 걸러야 한다), T04(비구성)는 전용 'X'
    for (int i = 0; i < AdvisorSyntheticData.TICKERS; i++) {
      jdbc.update("UPDATE tb_stock_master SET kospi200_sector = ? WHERE ticker = ?", i == 4 ? "X" : String.valueOf(1 + i % 3), AdvisorSyntheticData.ticker(i));
    }
    // T04: BASE 에 편입 — 이력 [첫날, BASE) 비구성 → [BASE, ∞) 구성
    jdbc.update("UPDATE tb_stock_master_history SET valid_to = ? WHERE ticker = 'T04'", BASE);
    jdbc.update("INSERT INTO tb_stock_master_history (ticker, valid_from, stock_name, market_type, security_group, is_kospi200, is_krx300, is_suspended, "
        + "is_administrative, is_active, snapshot_hash) VALUES ('T04', ?, '종목4', 'KOSPI', 'ST', TRUE, FALSE, FALSE, FALSE, TRUE, 'h2')", BASE);
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
  }

  private static void insertIndex(JdbcTemplate jdbc, String code, LocalDate d, double close) {
    BigDecimal c = BigDecimal.valueOf(close).setScale(4, RoundingMode.HALF_UP);
    jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES (?, ?, ?, ?, ?, ?)",
        code, d, c, c, c, c);
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    named = new NamedParameterJdbcTemplate(ds);
    properties = new AdvisorProperties(new MockEnvironment());
    properties.setMarkets(List.of("KOSPI", "KOSDAQ"));
    regimes = new MarketRegimeService(named, properties, new ThemeStrengthService(named, properties));
  }

  @Test
  @DisplayName("σ20 백분위는 기준일 이전 분포로만 매긴다 — 기준일 뒤에 극단 변동을 넣어도 σ·표본 수·백분위·국면이 그대로다")
  void volPercentileHasNoLookahead() {
    properties.getRegime().setIndexCode("9001");
    MarketRegimeService.VolReading before = regimes.vol(VBASE);
    assertThat(before.tradeDate()).isEqualTo(VBASE);
    assertThat(before.history()).as("꽉 찬 창(20..298)만, 기준일 제외").isEqualTo(279);
    assertThat(before.pct()).as("마지막 20일 ±3% 창이 분포 최댓값").isEqualTo(1.0);
    assertThat(before.sigma()).isCloseTo(0.03, within(0.002));
    assertThat(MarketRegimeService.classify(before, properties.getRegime())).isEqualTo(VolRegimeCode.HIGH);

    List<LocalDate> future = AdvisorSyntheticData.businessDays(VBASE.plusDays(1), 40);
    try {
      double close = 1000;
      for (int j = 0; j < future.size(); j++) {
        close *= j % 2 == 0 ? 1.10 : 0.90;
        insertIndex(jdbc, "9001", future.get(j), close);
      }
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
      MarketRegimeService.VolReading after = regimes.vol(VBASE);
      assertThat(after).as("미래 행이 σ·분포에 섞이지 않는다").isEqualTo(before);
      assertThat(regimes.vol(future.getLast()).sigma()).as("대조: 미래 기준일에서는 ±10% 새 행이 보인다").isGreaterThan(0.05);
      assertThat(regimes.vol(future.getLast()).history()).isGreaterThan(before.history());
    } finally {
      jdbc.update("DELETE FROM tb_stock_index_daily WHERE index_code = '9001' AND trade_date > ?", VBASE);
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    }
  }

  @Test
  @DisplayName("분포 표본이 vol-min-history-days 미만이면 UNKNOWN, 합성 국면은 추세 × 변동성 → 정책 표(BEAR·HIGH: LONG≤8, 확신≤0.65, AVOID≤4)")
  void composesTrendVolAndPolicy() {
    properties.getRegime().setIndexCode("9001");
    MarketRegimeService.VolReading early = regimes.vol(VOL_DATES.get(150));
    assertThat(early.history()).isEqualTo(130);
    assertThat(MarketRegimeService.classify(early, properties.getRegime())).as("130 < 250").isEqualTo(VolRegimeCode.UNKNOWN);

    MarketTrend bear = MarketTrend.builder().indexCode("9001").tradeDate(VBASE).code(MarketTrendCode.BEAR).rawCode(MarketTrendCode.BEAR).score(-3).build();
    MarketRegime regime = regimes.regime(VBASE, List.of(bear));
    assertThat(regime.labelText()).isEqualTo("BEAR·HIGH");
    assertThat(regime.trendScore()).isEqualTo(-3);
    assertThat(regime.policy()).isEqualTo(new MarketRegime.Policy("regime-policy-v1", 8, 0.65, 4));

    MarketRegime noTrend = regimes.regime(VBASE, List.of());
    assertThat(noTrend.trend()).isNull();
    assertThat(noTrend.policy()).as("추세가 없으면 정책도 없다(기존 가드만)").isNull();
    assertThat(noTrend.vol()).isEqualTo(VolRegimeCode.HIGH);
  }

  @Test
  @DisplayName("테마는 그날 KOSPI200 구성(PIT)만 대분류로 묶는다 — 비구성 코드 '2' 없음, 편입일(BASE) 전날에는 T04 테마 'X' 없음, rs 는 중앙값 − KOSPI")
  void themesArePointInTime() {
    properties.getTheme().setMinMembers(1);
    ThemeStrengthService service = new ThemeStrengthService(named, properties);

    Map<String, MarketRegime.Theme> atBase = service.themes(BASE).stream().collect(Collectors.toMap(MarketRegime.Theme::code, t -> t));
    assertThat(atBase.keySet()).containsExactlyInAnyOrder("1", "3", "X");
    // 테마 '1' = {0,6,12,18,24,30,36}: ret_20d = i/40 → 중앙값 18/40, ret_60d = (3i mod 40)/40 → 중앙값 18/40, KOSPI 수익률 0
    MarketRegime.Theme one = atBase.get("1");
    assertThat(one.members()).isEqualTo(7);
    assertThat(one.rs20()).isCloseTo(18 / 40.0, within(1e-9));
    assertThat(one.rs60()).isCloseTo(18 / 40.0, within(1e-9));
    assertThat(one.rs5()).as("합성 데이터에 ret_5d 없음").isNull();
    assertThat(one.breadth()).as("종가 ≥ 100 > ma20 90").isEqualTo(1.0);
    assertThat(one.strength()).isEqualTo(ThemeStrength.STRONG);
    assertThat(one.leaders()).hasSize(3);
    assertThat(atBase.get("X").members()).as("BASE 에 편입된 T04").isEqualTo(1);
    assertThat(service.themes(BASE).getFirst().code()).as("rs20 내림차순 — '3' 중앙값 20/40").isEqualTo("3");

    LocalDate dayBefore = DATES.get(DATES.size() - 2);
    assertThat(service.themes(dayBefore)).extracting(MarketRegime.Theme::code).as("편입 전날에는 T04 가 구성이 아니다").containsExactlyInAnyOrder("1", "3");

    properties.getTheme().setMinMembers(3);
    assertThat(new ThemeStrengthService(named, properties).themes(BASE)).extracting(MarketRegime.Theme::code).as("구성 3 미만 대분류 제외")
        .containsExactlyInAnyOrder("1", "3");
  }

  @Test
  @DisplayName("후보 features.theme 은 그날 구성종목일 때만 현재 대분류 코드 — 비구성 종목은 키가 없다")
  void candidateThemeFeatureIsPointInTime() {
    properties.setPickUniverse(PickUniverse.ALL);
    CandidateScreeningService screening = new CandidateScreeningService(named, new WeightSetRepository(jdbc), properties);
    List<CandidateRow> candidates = screening.screen(BASE).candidates();
    assertThat(candidates).isNotEmpty();
    for (CandidateRow c : candidates) {
      int i = Integer.parseInt(c.ticker().substring(1));
      boolean member = AdvisorSyntheticData.kospi200(i) || i == 4;
      if (member) {
        assertThat(c.features()).as(c.ticker()).containsEntry("theme", i == 4 ? "X" : String.valueOf(1 + i % 3));
      } else {
        assertThat(c.features()).as(c.ticker()).doesNotContainKey("theme");
      }
    }
  }

  @Test
  @DisplayName("regime_json 은 헤더 맨 뒤 컬럼으로 저장되고 같은 레코드로 읽힌다 — 없으면 null")
  void regimeJsonRoundTrip() {
    Long runId = jdbc.queryForObject("INSERT INTO tb_advisor_run (job_type, trigger_type, status, created_at, updated_at) "
        + "VALUES ('ADVISE', 'API', 'SUCCESS', NOW(), NOW()) RETURNING run_id", Long.class);
    MarketRegime regime = new MarketRegime("0001", BASE, MarketTrendCode.SIDEWAYS, 1, VolRegimeCode.NORMAL, 0.5423, 0.0112, 1200,
        new MarketRegime.Policy("regime-policy-v1", 10, 0.80, 2),
        List.of(new MarketRegime.Theme("5", 30, 0.01, 0.031, 0.02, 0.62, ThemeStrength.STRONG, List.of("삼성전자", "SK하이닉스"))));
    AdviceWriter writer = new AdviceWriter(jdbc);
    AdviceHeader header = AdviceHeader.builder().runId(runId).baseDate(BASE).adviceKind(AdviceKind.DAILY).variant(AdviceVariant.LIVE).horizonDays(5)
        .regime(regime).build();
    long id = writer.insertHeader(header);
    long plain = writer.insertHeader(header.toBuilder().variant(AdviceVariant.QUANT_TOPN).regime(null).build());

    assertThat(writer.findById(id).orElseThrow().regime()).isEqualTo(regime);
    assertThat(writer.findById(plain).orElseThrow().regime()).isNull();
    assertThat(jdbc.queryForObject("SELECT regime_json->'policy'->>'convictionCap' FROM tb_advisor_advice WHERE advice_id = ?", String.class, id))
        .isEqualTo("0.8");
    List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'tb_advisor_advice' ORDER BY ordinal_position",
        String.class);
    assertThat(columns.getLast()).as("컬럼은 맨 뒤에 추가").isEqualTo("regime_json");
  }
}

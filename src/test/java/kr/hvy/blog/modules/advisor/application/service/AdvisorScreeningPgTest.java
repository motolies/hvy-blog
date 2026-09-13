package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.SignalIcWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport;
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
 * 합성 데이터(40종목 × 30영업일)로 스크리닝·IC·가중치 산출을 실제 PostgreSQL 에서 검증한다.
 * <p>
 * adj_close(t) = 100 + i·t 라 5일 초과수익이 종목 번호 i 에 단조 증가하고, MOM_20D = i/40, FOREIGN_FLOW ∝ (i−20) 도 단조라
 * 두 시그널의 rank-IC 는 정확히 1 이어야 한다. 지수는 상수(2500)라 벤치마크 수익률 0. 룩어헤드는 기준일 뒤 행을 넣어도 결과가 같은지로 검사한다.
 */
@Testcontainers
class AdvisorScreeningPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final int TICKERS = 40;
  static final List<LocalDate> DATES = businessDays(LocalDate.of(2026, 8, 3), 30);
  static final LocalDate BASE = DATES.getLast();

  private JdbcTemplate jdbc;
  private CandidateScreeningService screening;
  private SignalIcService icService;
  private MarketFeatureService marketFeatures;
  private AdvisorProperties properties;

  @BeforeAll
  static void schemaAndData() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-seed.sql"));
    }
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    seedStockData(jdbc);
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
    properties = new AdvisorProperties(new MockEnvironment());
    properties.getIc().setMinNEff(1);
    WeightSetRepository weightSets = new WeightSetRepository(jdbc);
    screening = new CandidateScreeningService(named, weightSets, properties);
    icService = new SignalIcService(named, new SignalIcWriter(new BatchUpsertSupport(jdbc), jdbc), weightSets, properties);
    kr.hvy.blog.modules.stock.application.service.MarketCalendarService calendar =
        org.mockito.Mockito.mock(kr.hvy.blog.modules.stock.application.service.MarketCalendarService.class);
    org.mockito.Mockito.when(calendar.isTradingDay(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
      DayOfWeek day = ((LocalDate) inv.getArgument(0)).getDayOfWeek();
      return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    });
    marketFeatures = new MarketFeatureService(named, properties, new MarketTrendService(named, properties), new TradingCalendar(calendar));
    jdbc.update("TRUNCATE tb_advisor_signal_ic_daily");
    jdbc.update("DELETE FROM tb_stock_daily_metric WHERE trade_date > ?", BASE);
    jdbc.update("DELETE FROM tb_stock_daily_price WHERE trade_date > ?", BASE);
    jdbc.update("DELETE FROM tb_stock_index_daily WHERE trade_date > ?", BASE);
  }

  @Test
  @DisplayName("스크리닝: 유니버스 40 → 1차 컷 39 → 섹터당 ≤4 후보, 점수 내림차순, 시그널 스냅샷 동결")
  void screensWithSectorCapAndSnapshots() {
    ScreeningResult result = screening.screen(BASE);
    assertThat(result.universeSize()).isEqualTo(40);
    assertThat(result.cutSize()).as("T00 은 모멘텀 0·거래대금 급증 없음이라 컷").isEqualTo(39);
    List<CandidateRow> candidates = result.candidates();
    assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(16);
    Map<String, Long> perSector = candidates.stream().collect(Collectors.groupingBy(CandidateRow::sectorCode, Collectors.counting()));
    assertThat(perSector.values()).allMatch(n -> n <= 4);
    for (int i = 0; i < candidates.size(); i++) {
      assertThat(candidates.get(i).quantRank()).isEqualTo(i + 1);
      if (i > 0) {
        assertThat(candidates.get(i).quantScore()).isLessThanOrEqualTo(candidates.get(i - 1).quantScore());
      }
      assertThat(candidates.get(i).signals().values()).allMatch(v -> v.pct() >= 0 && v.pct() <= 1);
      assertThat(candidates.get(i).signals().get("VALUE_RANK").pct()).as("밸류 없음 → 중립 0.5").isEqualTo(0.5);
      assertThat(candidates.get(i).signals().get("MOM_20D").w()).isEqualTo(0.12);
      assertThat(candidates.get(i).features()).containsKeys("r20", "tvRatio", "close");
      assertThat(candidates.get(i).benchIndexCode()).isIn("0001", "1001");
    }
    CandidateRow top = candidates.stream().filter(c -> c.ticker().equals("T39")).findFirst().orElseThrow();
    assertThat(top.signals().get("MOM_20D").pct()).as("가장 큰 모멘텀은 백분위 1.0").isEqualTo(1.0);
    assertThat(top.signals().get("MOM_20D").raw()).isCloseTo(39 / 40.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(result.weightSetId()).isPositive();
  }

  @Test
  @DisplayName("룩어헤드: 기준일 뒤에 극단값 행을 넣어도 기준일 스크리닝 결과는 같다")
  void futureRowsDoNotChangeScreening() {
    ScreeningResult before = screening.screen(BASE);
    LocalDate future = nextBusinessDay(BASE);
    jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES ('0001', ?, 1, 1, 1, 1)", future);
    for (int i = 0; i < TICKERS; i++) {
      insertPrice(jdbc, ticker(i), future, 1);
      jdbc.update("INSERT INTO tb_stock_daily_metric (ticker, trade_date, adj_close, ret_1d, ret_20d, ret_60d, ma_5, ma_20, ma_60, ma_120, dist_high_52w, "
              + "tv_ratio_5_60, tv_avg_5d, tv_avg_60d, foreign_net_5d, institution_net_5d) VALUES (?, ?, 1, 0.5, 9.0, 9.0, 0, 0, 0, 0, 0, 9.0, 2e9, 2e9, 9e12, 9e12)",
          ticker(i), future);
    }
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_sector_daily");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_market_breadth_daily");
    ScreeningResult after = screening.screen(BASE);
    assertThat(after.candidates().stream().map(CandidateRow::ticker).toList())
        .containsExactlyElementsOf(before.candidates().stream().map(CandidateRow::ticker).toList());
    assertThat(after.candidates().stream().map(CandidateRow::quantScore).toList())
        .containsExactlyElementsOf(before.candidates().stream().map(CandidateRow::quantScore).toList());
  }

  @Test
  @DisplayName("IC: 단조 시그널(MOM_20D·FOREIGN_FLOW)의 일별 rank-IC 는 1.0, 표본 40, LEAD 는 실현 수익률에만")
  void rankIcOfMonotoneSignalsIsOne() {
    LocalDate to = DATES.get(24); // d+5 = DATES[29] 까지 존재
    List<SignalIcRow> rows = icService.compute(DATES.getFirst(), to, 5);
    assertThat(rows).isNotEmpty();
    List<SignalIcRow> mom = rows.stream().filter(r -> r.signalCode().equals("MOM_20D")).toList();
    assertThat(mom).hasSize(25);
    assertThat(mom).allMatch(r -> r.rankIc() > 0.999 && r.n() == 40);
    assertThat(rows.stream().filter(r -> r.signalCode().equals("FOREIGN_FLOW"))).allMatch(r -> r.rankIc() > 0.999);
    assertThat(rows.stream().map(SignalIcRow::signalCode).collect(Collectors.toSet())).doesNotContain("VALUE_RANK", "GLOBAL_LINK");
    assertThat(icService.latestScorableDate(5)).contains(to);

    assertThat(icService.computeAndStore(DATES.getFirst(), to)).isEqualTo(rows.size());
    assertThat(icService.computeIncremental(runAll())).as("이미 최신까지 계산됨").isEmpty();

    WeightSet proposed = icService.proposeWeightSet(to, WeightSetSource.BACKFILL, null).orElseThrow();
    Map<String, SignalWeightRow> byCode = proposed.byCode();
    assertThat(byCode.get("MOM_20D").multiplier()).as("IC 1.0 → raw 33 → 상한").isEqualTo(2.0);
    assertThat(byCode.get("MOM_20D").icMean()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(byCode.get("VALUE_RANK").multiplier()).isEqualTo(1.0);
    double sum = proposed.weights().stream().filter(SignalWeightRow::enabled).mapToDouble(SignalWeightRow::weight).sum();
    assertThat(sum).as("가중치는 소수 6자리로 반올림되므로 합 오차 ≤ 1e-5").isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(proposed.source()).isEqualTo(WeightSetSource.BACKFILL);
  }

  @Test
  @DisplayName("IC 증분 상한: 행이 없으면 backfill-from(2020) 이 아니라 최근 incremental-max-days 만 월 청크로 계산하고 공백을 돌려준다")
  void incrementalIsCappedAndChunked() {
    properties.getIc().setIncrementalMaxDays(10);
    LocalDate to = DATES.get(24); // 계산 가능한 마지막 기준일 (d+5 = DATES[29])
    List<String> chunkNames = new ArrayList<>();
    SignalIcService.IncrementalResult result = icService.computeIncremental((name, body) -> {
      chunkNames.add(name);
      body.run();
      return true;
    }).orElseThrow();

    assertThat(result.to()).isEqualTo(to);
    assertThat(result.from()).as("end − 10일").isEqualTo(to.minusDays(10));
    assertThat(result.truncated()).isTrue();
    assertThat(result.gapFrom()).as("공백은 원래 시작일(backfill-from)부터").isEqualTo(LocalDate.parse(properties.getIc().getBackfillFrom()));
    assertThat(result.chunks()).isEqualTo(1);
    assertThat(chunkNames).containsExactly("IC:" + to.minusDays(10).getYear() + "-" + String.format("%02d", to.minusDays(10).getMonthValue()));
    LocalDate minStored = jdbc.queryForObject("SELECT MIN(trade_date) FROM tb_advisor_signal_ic_daily", LocalDate.class);
    assertThat(minStored).as("상한 밖(공백)은 저장되지 않는다").isAfterOrEqualTo(to.minusDays(10));
    assertThat(result.rows()).isPositive();

    assertThat(icService.computeIncremental(runAll())).as("두 번째 호출은 최신까지 계산된 상태라 empty").isEmpty();
  }

  /** 청크를 그냥 실행하는 runner (단계 기록 없음) */
  private static SignalIcService.ChunkRunner runAll() {
    return (name, body) -> {
      body.run();
      return true;
    };
  }

  @Test
  @DisplayName("시장 특징: 지수·σ·섹터가 기준일 기준으로 나온다")
  void marketFeatures() {
    MarketFeatures f = marketFeatures.features(BASE);
    assertThat(f.indices()).extracting(MarketFeatures.IndexFeature::code).contains("0001", "1001");
    assertThat(f.indices().getFirst().close()).isEqualTo(2500.0);
    assertThat(f.sigma5d()).containsKeys("0001", "1001");
    assertThat(f.sigma5d().get("0001")).isEqualTo(0.0);
    assertThat(f.topSectors()).isNotEmpty().hasSizeLessThanOrEqualTo(8);
    assertThat(f.topSectors().getFirst().members()).isEqualTo(10);
    assertThat(f.flows()).isEmpty();
    assertThat(f.global()).isEmpty();
    // advice-v2: 관측 기준일·적용 구간·규칙 추세
    assertThat(f.globalAsOf()).as("해외 데이터 없음").isNull();
    assertThat(f.globalAgeTradingDays()).isNull();
    assertThat(f.flowAsOf()).isNull();
    assertThat(f.sectorAsOf()).isEqualTo(BASE);
    assertThat(f.entryDate()).isAfter(BASE);
    assertThat(f.exitDate()).isAfter(f.entryDate());
    assertThat(f.trends()).extracting(kr.hvy.blog.modules.advisor.domain.model.MarketTrend::indexCode).containsExactly("0001", "1001");
    kr.hvy.blog.modules.advisor.domain.model.MarketTrend kospi = f.trendOf("0001");
    assertThat(kospi.tradeDate()).isEqualTo(BASE);
    assertThat(kospi.components()).as("지수 상수 2500 → MA·60일 성분 0, 모든 종목이 MA20(90) 위라 breadth +1")
        .containsEntry("ma20", 0).containsEntry("ma60", 0).containsEntry("ma120", 0).containsEntry("ret60", 0).containsEntry("breadth", 1);
    assertThat(kospi.score()).isEqualTo(1);
    assertThat(kospi.code()).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.SIDEWAYS);
    assertThat(kospi.breadth()).isEqualTo(1.0);
    assertThat(kospi.days()).isEqualTo(30);
    assertThat(kospi.base()).isNotNull();
    assertThat(kospi.base().episodes()).as("진행 중인 구간만 있어 완료된 에피소드 없음").isZero();
    assertThat(kospi.base().fwd5().n()).isEqualTo(25);
    assertThat(kospi.base().fwd5().mean()).isEqualTo(0.0);
    assertThat(f.dataAsOf()).containsEntry("domestic", BASE.toString()).containsEntry("flowProvisional", true);
  }

  // ---------- 합성 데이터 ----------

  static void seedStockData(JdbcTemplate jdbc) {
    jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES ('0001', 'KOSPI'), ('1001', 'KOSDAQ')");
    for (LocalDate d : DATES) {
      jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES ('0001', ?, 2500, 2500, 2500, 2500), ('1001', ?, 2500, 2500, 2500, 2500)", d, d);
    }
    for (int i = 0; i < TICKERS; i++) {
      String t = ticker(i);
      jdbc.update("INSERT INTO tb_stock_master (ticker, stock_name, market_type, security_group, created_at, updated_at) VALUES (?, ?, ?, 'ST', NOW(), NOW())",
          t, "종목" + i, i % 2 == 0 ? "KOSPI" : "KOSDAQ");
      jdbc.update("INSERT INTO tb_stock_sector_map (ticker, sector_code, valid_from, sector_name, source) VALUES (?, ?, ?, ?, 'KRX')",
          t, "S" + (i % 4), DATES.getFirst(), "섹터" + (i % 4));
      for (int k = 0; k < DATES.size(); k++) {
        double close = 100 + i * k;
        insertPrice(jdbc, t, DATES.get(k), close);
        jdbc.update("INSERT INTO tb_stock_daily_metric (ticker, trade_date, adj_close, ret_1d, ret_20d, ret_60d, ma_5, ma_20, ma_60, ma_120, "
                + "dist_ma20, dist_ma60, high_52w, dist_high_52w, tv_avg_5d, tv_avg_60d, tv_ratio_5_60, vol_avg_20d, foreign_net_5d, institution_net_5d) "
                + "VALUES (?, ?, ?, ?, ?, ?, 90, 90, 90, 90, 0.1, 0.1, ?, ?, 2e9, 2e9, ?, 1000, ?, ?)",
            t, DATES.get(k), close, ((i * k) % 7) / 1000.0, i / 40.0, ((i * 3) % 40) / 40.0, close * 1.1, -(i % 10) / 100.0,
            1.0 + ((i * 7) % 40) / 40.0, (i - 20) * 1e8, (((i * 11) % 40) - 20) * 1e8);
      }
    }
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_adjust_factor");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_sector_daily");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_market_breadth_daily");
  }

  static void insertPrice(JdbcTemplate jdbc, String ticker, LocalDate date, double close) {
    BigDecimal price = BigDecimal.valueOf(close);
    jdbc.update("INSERT INTO tb_stock_daily_price (ticker, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, change_rate) "
        + "VALUES (?, ?, ?, ?, ?, ?, 1000, 2000000000, 0.5)", ticker, date, price, price, price, price);
  }

  static String ticker(int i) {
    return String.format("T%02d", i);
  }

  static List<LocalDate> businessDays(LocalDate start, int count) {
    List<LocalDate> days = new ArrayList<>();
    LocalDate d = start;
    while (days.size() < count) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        days.add(d);
      }
      d = d.plusDays(1);
    }
    return days;
  }

  static LocalDate nextBusinessDay(LocalDate d) {
    LocalDate n = d.plusDays(1);
    while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
      n = n.plusDays(1);
    }
    return n;
  }
}

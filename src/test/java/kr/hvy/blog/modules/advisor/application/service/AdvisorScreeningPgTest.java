package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.PickUniverse;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
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
 * <p>
 * 데이터는 {@link AdvisorSyntheticData} 공용 시드(advice-v6 부터 업종 지수 S0~S3 포함, 30영업일이라 rs60 은 null — non-null 경로는 MarketFeaturePgTest).
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
    // 합성 시드는 i 홀짝으로 KOSPI/KOSDAQ 를 나눈다 — 아래 단언(유니버스 40·IC n=40·HAVING ≥30)은 양시장 기준. KOSPI 한정은 kospiOnlyUniverse 가 따로 본다
    properties.setMarkets(List.of("KOSPI", "KOSDAQ"));
    // 스크리닝 메커니즘 단언(양시장·섹터 S1 포함)은 픽 필터 없이 본다. KOSPI200 필터는 kospi200PickUniverse 가 따로 본다
    properties.setPickUniverse(PickUniverse.ALL);
    WeightSetRepository weightSets = new WeightSetRepository(jdbc);
    screening = new CandidateScreeningService(named, weightSets, properties);
    icService = new SignalIcService(named, new SignalIcWriter(new BatchUpsertSupport(jdbc), jdbc), weightSets, properties);
    kr.hvy.blog.modules.stock.application.service.MarketCalendarService calendar =
        org.mockito.Mockito.mock(kr.hvy.blog.modules.stock.application.service.MarketCalendarService.class);
    org.mockito.Mockito.when(calendar.isTradingDay(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
      DayOfWeek day = ((LocalDate) inv.getArgument(0)).getDayOfWeek();
      return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    });
    marketFeatures = new MarketFeatureService(named, properties, new MarketTrendService(named, properties), new TradingCalendar(calendar),
        new GlobalLinkService(named, properties), new MarketRegimeService(named, properties, new ThemeStrengthService(named, properties)));
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
      // advice-v6: 업종 지수 30행 → rs5·rs20 은 있고 rs60 은 없다(키 생략) → secCons null, SECTOR_MOM_60D 는 중립 0.5
      assertThat(candidates.get(i).features()).containsKeys("secRs5", "secRs20").doesNotContainKey("secRs60");
      assertThat(AdvicePromptBuilder.secCons(candidates.get(i).features())).isNull();
      assertThat(candidates.get(i).signals().get("SECTOR_MOM_20D").raw()).isNotNull();
      assertThat(candidates.get(i).signals().get("SECTOR_MOM_60D").pct()).isEqualTo(0.5);
      assertThat(candidates.get(i).signals().get("SECTOR_MOM_60D").raw()).isNull();
    }
    CandidateRow s0 = candidates.stream().filter(c -> c.sectorCode().equals("S0")).findFirst().orElseThrow();
    CandidateRow s1 = candidates.stream().filter(c -> c.sectorCode().equals("S1")).findFirst().orElseThrow();
    assertThat((Double) s0.features().get("secRs20")).as("S0 업종 지수 +0.5%/일, KOSPI 상수 → 양수").isPositive();
    assertThat((Double) s1.features().get("secRs20")).as("S1 −0.5%/일 → 음수").isNegative();
    CandidateRow top = candidates.stream().filter(c -> c.ticker().equals("T39")).findFirst().orElseThrow();
    assertThat(top.signals().get("MOM_20D").pct()).as("가장 큰 모멘텀은 백분위 1.0").isEqualTo(1.0);
    assertThat(top.signals().get("MOM_20D").raw()).isCloseTo(39 / 40.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(result.weightSetId()).isPositive();
  }

  @Test
  @DisplayName("KOSPI 한정(advisor.markets 기본, advice-v5): 유니버스 20 → 컷 19, 후보 전부 KOSPI·벤치 0001, 백분위는 KOSPI 안에서 다시 매겨진다")
  void kospiOnlyUniverse() {
    properties.setMarkets(List.of("KOSPI"));
    ScreeningResult result = screening.screen(BASE);
    assertThat(result.universeSize()).as("짝수 종목만").isEqualTo(20);
    assertThat(result.cutSize()).as("T00(KOSPI) 만 컷").isEqualTo(19);
    assertThat(result.candidates()).isNotEmpty()
        .allMatch(c -> "KOSPI".equals(c.marketType()) && "0001".equals(c.benchIndexCode()));
    assertThat(result.candidates()).extracting(CandidateRow::ticker).doesNotContain("T39", "T37", "T01");
    CandidateRow top = result.candidates().stream().filter(c -> c.ticker().equals("T38")).findFirst().orElseThrow();
    assertThat(top.signals().get("MOM_20D").pct()).as("KOSPI 안 최대 모멘텀이 백분위 1.0 (양시장이면 T39 가 1.0)").isEqualTo(1.0);
    assertThat(top.signals().get("MOM_20D").raw()).isCloseTo(38 / 40.0, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  @DisplayName("KOSPI200 픽 유니버스(advice-v7): 후보 전원이 기준일 구성종목이고, 유니버스·1차 컷·백분위·feat 행 수는 필터 전과 같다")
  void kospi200PickUniverse() {
    ScreeningResult all = screening.screen(BASE);
    properties.setPickUniverse(PickUniverse.KOSPI200);
    ScreeningResult k200 = screening.screen(BASE);

    assertThat(k200.candidates()).isNotEmpty()
        .allMatch(c -> AdvisorSyntheticData.kospi200(Integer.parseInt(c.ticker().substring(1))), "합성 KOSPI200 구성종목");
    assertThat(k200.candidates()).extracting(CandidateRow::ticker).doesNotContain("T39", "T34", "T28");
    assertThat(all.candidates()).extracting(CandidateRow::ticker).as("필터가 실제로 무언가를 걸렀다").contains("T39");
    assertThat(k200.universeSize()).as("백분위 모집단 불변").isEqualTo(all.universeSize()).isEqualTo(40);
    assertThat(k200.cutSize()).isEqualTo(all.cutSize());
    assertThat(k200.candidates()).extracting(CandidateRow::quantRank).as("순위는 거른 뒤 1부터 다시")
        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, k200.candidates().size()).boxed().toList());
    Map<String, CandidateRow> allByTicker = all.candidates().stream().collect(Collectors.toMap(CandidateRow::ticker, c -> c));
    for (CandidateRow c : k200.candidates()) {
      if (allByTicker.containsKey(c.ticker())) {
        assertThat(c.signals()).as(c.ticker() + " 백분위는 유니버스 전체 기준 그대로").isEqualTo(allByTicker.get(c.ticker()).signals());
        assertThat(c.quantScore()).isEqualTo(allByTicker.get(c.ticker()).quantScore());
      }
    }
    // IC·백분위가 쓰는 feat 행 수 = 유니버스 행 수 (이력 LEFT JOIN 이 행을 늘리거나 줄이지 않는다), 30영업일 전체
    Map<String, Object> range = Map.of("from", DATES.getFirst(), "to", BASE, "markets", properties.getMarkets());
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
    Integer featRows = named.queryForObject(FeatureSql.featureCtes() + " SELECT COUNT(*) FROM feat", range, Integer.class);
    Integer universeRows = named.queryForObject("SELECT COUNT(*) FROM vw_stock_universe_daily u JOIN tb_stock_master ms ON ms.ticker = u.ticker "
        + "WHERE u.trade_date BETWEEN :from AND :to AND ms.market_type IN (:markets)", range, Integer.class);
    assertThat(featRows).isEqualTo(universeRows).isEqualTo(40 * DATES.size());
  }

  @Test
  @DisplayName("KOSPI200 구성 여부는 PIT: 이력 행이 바뀐 날부터만 참이고, 이력 시작 전 날짜는 NULL(현재 마스터 값으로 채우지 않는다)")
  void kospi200IsPointInTime() {
    LocalDate joined = DATES.get(25);
    try {
      // T04: 비구성 → D25 편입. T06: 이력 시작을 D10 으로 늦춰 그 전은 모름
      jdbc.update("UPDATE tb_stock_master_history SET valid_to = ? WHERE ticker = 'T04'", joined);
      jdbc.update("INSERT INTO tb_stock_master_history (ticker, valid_from, stock_name, market_type, security_group, is_kospi200, is_krx300, "
          + "is_suspended, is_administrative, is_active, snapshot_hash) VALUES ('T04', ?, '종목4', 'KOSPI', 'ST', TRUE, FALSE, FALSE, FALSE, TRUE, 'h2')", joined);
      jdbc.update("UPDATE tb_stock_master_history SET valid_from = ? WHERE ticker = 'T06'", DATES.get(10));
      NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
      Map<String, Object> range = Map.of("from", DATES.getFirst(), "to", BASE, "markets", properties.getMarkets());
      Map<String, Boolean> t04 = new java.util.HashMap<>();
      Map<String, Boolean> t06 = new java.util.HashMap<>();
      named.query(FeatureSql.featureCtes() + " SELECT ticker, trade_date, is_kospi200 FROM feat WHERE ticker IN ('T04', 'T06')", range, rs -> {
        Boolean flag = (Boolean) rs.getObject("is_kospi200");
        (rs.getString("ticker").equals("T04") ? t04 : t06).put(rs.getObject("trade_date", LocalDate.class).toString(), flag);
      });
      assertThat(t04.get(DATES.get(24).toString())).isFalse();
      assertThat(t04.get(joined.toString())).isTrue();
      assertThat(t04.get(BASE.toString())).isTrue();
      assertThat(t06.get(DATES.get(9).toString())).as("이력 시작 전은 모름").isNull();
      assertThat(t06.get(DATES.get(10).toString())).isTrue();
    } finally {
      jdbc.update("DELETE FROM tb_stock_master_history WHERE ticker = 'T04' AND valid_from = ?", joined);
      jdbc.update("UPDATE tb_stock_master_history SET valid_to = NULL WHERE ticker = 'T04'");
      jdbc.update("UPDATE tb_stock_master_history SET valid_from = ? WHERE ticker = 'T06'", DATES.getFirst());
    }
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
    assertThat(rows.stream().map(SignalIcRow::signalCode).collect(Collectors.toSet())).doesNotContain("VALUE_RANK", "GLOBAL_LINK")
        .as("값이 전부 NULL 인 시그널(rs60 없음)은 IC 행이 생기지 않고 게이트도 막지 않는다").doesNotContain("SECTOR_MOM_60D");
    assertThat(icService.latestScorableDate(5)).contains(to);

    assertThat(icService.computeAndStore(DATES.getFirst(), to)).isEqualTo(rows.size());
    assertThat(icService.computeIncremental(5, runAll())).as("이미 최신까지 계산됨").isEmpty();

    WeightSet proposed = icService.proposeWeightSet(to, WeightSetSource.BACKFILL, null).orElseThrow();
    // h=5 회귀 고정(M5): 창 120, 세트 호라이즌 5. n_eff 는 학습 시그널 중 최소(20일 창이 차야 값이 생기는 시그널은 IC 5일 → 5/5 = 1.0)
    assertThat(proposed.horizonDays()).isEqualTo(5);
    assertThat(proposed.windowDays()).isEqualTo(120);
    assertThat(proposed.nEff()).isEqualTo(1.0);
    assertThat(byCodeNDays(proposed, "MOM_20D")).as("MOM_20D 는 IC 25일").isEqualTo(25);
    assertThat(proposed.reason()).isEqualTo("BACKFILL IC 창 120일, n_eff 1.0");
    Map<String, SignalWeightRow> byCode = proposed.byCode();
    assertThat(byCode.get("MOM_20D").multiplier()).as("IC 1.0 → raw 33 → 상한").isEqualTo(2.0);
    assertThat(byCode.get("MOM_20D").icMean()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(byCode.get("VALUE_RANK").multiplier()).isEqualTo(1.0);
    double sum = proposed.weights().stream().filter(SignalWeightRow::enabled).mapToDouble(SignalWeightRow::weight).sum();
    double baseSum = SignalCode.scorable().stream().mapToDouble(SignalCode::getBaseWeight).sum();
    assertThat(sum).as("재정규화로 Σweight = Σbase(1.10, advice-v6 시드) — 소수 6자리 반올림이라 오차 ≤ 1e-5").isCloseTo(baseSum, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(byCode.get("SECTOR_MOM_60D").multiplier()).as("IC 없음 → 배수 1.0 유지").isEqualTo(1.0);
    assertThat(proposed.source()).isEqualTo(WeightSetSource.BACKFILL);
  }

  @Test
  @DisplayName("IC 증분 상한: 행이 없으면 backfill-from(2020) 이 아니라 최근 incremental-max-days 만 월 청크로 계산하고 공백을 돌려준다")
  void incrementalIsCappedAndChunked() {
    properties.getIc().setIncrementalMaxDays(10);
    LocalDate to = DATES.get(24); // 계산 가능한 마지막 기준일 (d+5 = DATES[29])
    List<String> chunkNames = new ArrayList<>();
    SignalIcService.IncrementalResult result = icService.computeIncremental(5, (name, body) -> {
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

    assertThat(icService.computeIncremental(5, runAll())).as("두 번째 호출은 최신까지 계산된 상태라 empty").isEmpty();
  }

  @Test
  @DisplayName("M5 룩어헤드: 호라이즌 h 의 IC 는 d+h 영업일이 캘린더에 있는 d 만 계산한다 — 30영업일이면 h=20 은 앞 10일, h=60 은 0일")
  void horizonIcRequiresExitClose() {
    List<SignalIcRow> h20 = icService.compute(DATES.getFirst(), BASE, 20);
    assertThat(h20).isNotEmpty().allMatch(r -> r.horizonDays() == 20);
    assertThat(h20.stream().map(SignalIcRow::tradeDate).max(LocalDate::compareTo)).as("d+20 = DATES[29] 가 마지막").contains(DATES.get(9));
    assertThat(h20.stream().filter(r -> r.signalCode().equals("MOM_20D"))).hasSize(10).allMatch(r -> r.rankIc() > 0.999 && r.n() == 40);
    assertThat(icService.latestScorableDate(20)).contains(DATES.get(9));
    assertThat(icService.compute(DATES.getFirst(), BASE, 60)).as("d+60 이 없으면 IC 행이 없다").isEmpty();
    assertThat(icService.latestScorableDate(60)).isEmpty();
  }

  @Test
  @DisplayName("M5: h=20 IC 는 별도 행으로 저장되어 h=5 행을 덮어쓰지 않고, 증분은 호라이즌마다 따로 돌며 계산할 날이 없는 60·180 은 빠진다")
  void horizonIcStoredSeparately() {
    int h5 = icService.computeAndStore(DATES.getFirst(), BASE, 5);
    List<Map<String, Object>> before = jdbc.queryForList(
        "SELECT signal_code, trade_date, rank_ic, n FROM tb_advisor_signal_ic_daily WHERE horizon_days = 5 ORDER BY 1, 2");
    int h20 = icService.computeAndStore(DATES.getFirst(), BASE, 20);
    assertThat(h20).isPositive().isLessThan(h5);
    assertThat(jdbc.queryForList("SELECT signal_code, trade_date, rank_ic, n FROM tb_advisor_signal_ic_daily WHERE horizon_days = 5 ORDER BY 1, 2"))
        .as("h=5 행 불변").isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_signal_ic_daily WHERE horizon_days = 20", Integer.class)).isEqualTo(h20);

    jdbc.update("TRUNCATE tb_advisor_signal_ic_daily");
    List<SignalIcService.IncrementalResult> results = icService.computeIncremental(runAll());
    assertThat(results).extracting(SignalIcService.IncrementalResult::horizonDays).as("IC 대상 5·20·60·180 중 계산할 날이 있는 것만").containsExactly(5, 20);
    assertThat(results).extracting(SignalIcService.IncrementalResult::to).containsExactly(DATES.get(24), DATES.get(9));
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT trade_date) FROM tb_advisor_signal_ic_daily WHERE horizon_days = 20", Integer.class)).isEqualTo(10);
    assertThat(icService.computeIncremental(runAll())).as("모두 최신").isEmpty();
  }

  @Test
  @DisplayName("M5: h=20 가중치는 그 호라이즌 창(480)·n_eff = IC 일수/20 으로 산출하고, 첫 학습은 DAILY 세트의 사전 가중치에서 출발한다. 모니터링 호라이즌은 거부")
  void horizonWeightSetUsesItsOwnWindow() {
    icService.computeAndStore(DATES.getFirst(), BASE, 5);
    icService.computeAndStore(DATES.getFirst(), BASE, 20);
    properties.getIc().setMinNEff(1);
    assertThat(icService.proposeWeightSet(20, DATES.get(9), WeightSetSource.BACKFILL, null)).as("IC 10일 / 20 = 0.5 < 게이트 1").isEmpty();

    properties.getIc().setMinNEff(0);
    WeightSet h20 = icService.proposeWeightSet(20, DATES.get(9), WeightSetSource.BACKFILL, null).orElseThrow();
    assertThat(h20.horizonDays()).isEqualTo(20);
    assertThat(h20.windowDays()).isEqualTo(480);
    assertThat(h20.nEff()).isEqualTo(0.5);
    assertThat(h20.reason()).isEqualTo("BACKFILL h=20 IC 창 480일, n_eff 0.5");
    SignalWeightRow mom = h20.byCode().get("MOM_20D");
    assertThat(mom.nDays()).as("h=5 IC 행이 섞이지 않는다").isEqualTo(10);
    assertThat(mom.icMean()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(mom.baseWeight()).as("DAILY 시드의 사전 가중치").isEqualTo(0.12);
    // 축소: m̂ = 1 + 0.5/(0.5+24)·(1/0.03 − 1) ≈ 1.66 — h=5(n_eff 5)의 상한 2.0 보다 작다
    assertThat(mom.multiplier()).isCloseTo(1 + 0.5 / 24.5 * (1 / 0.03 - 1), org.assertj.core.data.Offset.offset(1e-5));

    assertThatThrownBy(() -> icService.proposeWeightSet(60, DATES.get(9), WeightSetSource.BACKFILL, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 세트 안 시그널의 IC 일수 */
  private static Integer byCodeNDays(WeightSet set, String code) {
    return set.byCode().get(code).nDays();
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
    // advice-v6 null 경로: 업종 지수 30행 → rs5·rs20 은 있고 rs60·mom 은 null, consistent 는 false. mom 이 전부 null 이면 v5 처럼 cw5 순(동률은 코드 순)
    assertThat(f.topSectors()).extracting(MarketFeatures.SectorFeature::code).containsExactly("S0", "S1", "S2", "S3");
    assertThat(f.topSectors()).allMatch(s -> s.rs5() != null && s.rs20() != null && s.rs60() == null && s.mom() == null && !s.consistent());
    assertThat(f.topSectors().getFirst().rs5()).as("S0 +0.5%/일 vs KOSPI 상수").isPositive();
    assertThat(f.topSectors().get(1).rs5()).as("S1 −0.5%/일").isNegative();
    assertThat(f.topSectors().get(1).overheated()).isFalse();
    assertThat(f.topSectors().getFirst().overheated()).as("KOSPI σ_5d 가 0(상수)이라 양수 5일 수익률은 전부 과열로 판정된다 — 합성 데이터의 퇴화 경계").isTrue();
    assertThat(f.bottomSectors()).as("섹터 4개 ≤ top 8 이라 bottom 은 비어 있다").isEmpty();
    assertThat(f.sectorIndexAsOf()).isEqualTo(BASE);
    assertThat(f.dataAsOf()).containsEntry("sectorIndex", BASE.toString());
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
    assertThat(f.links()).as("해외 데이터 없음 → 쌍 4개 모두 n=0").hasSize(4).allMatch(l -> l.n() == 0 && l.beta() == null);
  }

  // ---------- 합성 데이터 (공용 시드 AdvisorSyntheticData 와 같은 40종목 × 30영업일, DATES 도 동일) ----------

  static void seedStockData(JdbcTemplate jdbc) {
    AdvisorSyntheticData.seedStockData(jdbc);
  }

  static void insertPrice(JdbcTemplate jdbc, String ticker, LocalDate date, double close) {
    AdvisorSyntheticData.insertPrice(jdbc, ticker, date, close);
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

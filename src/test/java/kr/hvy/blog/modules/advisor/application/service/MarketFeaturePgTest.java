package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.AdvisorSyntheticData;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures.SectorFeature;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 섹터 기간 모멘텀(advice-v6)의 PG 검증 — non-null 경로. 공용 합성 데이터(30영업일) 앞에 65영업일을 보충해 KOSPI·업종 지수가 60행을 넘게 한다.
 * <p>
 * 보충 구간의 KOSPI 는 2500/2525 를 번갈아(σ_5d ≈ 0.016) 두고 기준일 창(30일)은 2500 상수라 기준일의 KOSPI 5·20·60일 수익률은 전부 0 — rs 는 업종 지수 수익률
 * 그대로다. S0 +0.5%/일(consistent), S1 −0.5%/일, S2 는 1200→920→950→1000 계단(rs5·rs20 &gt; 0, rs60 &lt; 0 — 단기 반등), S3 +0.1%/일(consistent 이지만 mom 낮음).
 * 백분위(4섹터, (rank−1)/3): rs5 S1&lt;S3&lt;S0&lt;S2, rs20 S1&lt;S3&lt;S2&lt;S0, rs60 S1&lt;S2&lt;S3&lt;S0 → mom S0 .889 · S2 .667 · S3 .444 · S1 0(세 구간 모두 최하위).
 * 캘린더(vw_stock_market_calendar)는 0001 행으로 정해지므로 이 확장은 이 컨테이너 안에서만 유효하다(공용 시드의 DATES 는 그대로).
 */
@Testcontainers
class MarketFeaturePgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static final List<LocalDate> DATES = AdvisorSyntheticData.DATES;
  static final LocalDate BASE = AdvisorSyntheticData.BASE;
  /** DATES 앞 65영업일 (2026-05-04 월 ~ 2026-07-31 금) — k = j − 65 */
  static final List<LocalDate> EXTRA = AdvisorSyntheticData.businessDays(LocalDate.of(2026, 5, 4), 65);

  private JdbcTemplate jdbc;
  private AdvisorProperties properties;
  private MarketFeatureService features;
  private CandidateScreeningService screening;

  @BeforeAll
  static void schemaAndData() throws Exception {
    assertThat(EXTRA.getLast()).isBefore(DATES.getFirst());
    assertThat(AdvisorSyntheticData.businessDays(EXTRA.getLast().plusDays(1), 1).getFirst()).as("보충 구간은 DATES 바로 앞에 이어진다").isEqualTo(DATES.getFirst());
    AdvisorSyntheticData.install(POSTGRES);
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    for (int j = 0; j < EXTRA.size(); j++) {
      LocalDate d = EXTRA.get(j);
      int kospi = j % 2 == 0 ? 2500 : 2525;
      jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) "
          + "VALUES ('0001', ?, ?, ?, ?, ?), ('1001', ?, 2500, 2500, 2500, 2500)", d, kospi, kospi, kospi, kospi, d);
      AdvisorSyntheticData.insertSectorIndices(jdbc, d, j - EXTRA.size());
    }
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_market_breadth_daily");
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
    properties = new AdvisorProperties(new MockEnvironment());
    properties.setMarkets(List.of("KOSPI", "KOSDAQ"));
    MarketCalendarService calendar = Mockito.mock(MarketCalendarService.class);
    Mockito.when(calendar.isTradingDay(ArgumentMatchers.any())).thenAnswer(inv -> {
      DayOfWeek day = ((LocalDate) inv.getArgument(0)).getDayOfWeek();
      return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    });
    features = new MarketFeatureService(named, properties, new MarketTrendService(named, properties), new TradingCalendar(calendar),
        new GlobalLinkService(named, properties));
    screening = new CandidateScreeningService(named, new WeightSetRepository(jdbc), properties);
  }

  @Test
  @DisplayName("업종 지수 rs5/rs20/rs60 은 KOSPI 대비 초과, mom 은 백분위 평균, consistent 는 세 구간 모두 양수, top 은 mom 순 — S0 > S2 > S3 > S1")
  void sectorMomentumFromIndustryIndex() {
    MarketFeatures f = features.features(BASE);
    List<SectorFeature> top = f.topSectors();
    assertThat(top).extracting(SectorFeature::code).containsExactly("S0", "S2", "S3", "S1");
    assertThat(f.bottomSectors()).as("섹터 4개 ≤ top 8").isEmpty();
    assertThat(f.sectorIndexAsOf()).isEqualTo(BASE);
    assertThat(f.dataAsOf()).containsEntry("sectorIndex", BASE.toString());

    Map<String, SectorFeature> byCode = new java.util.HashMap<>();
    top.forEach(s -> byCode.put(s.code(), s));
    SectorFeature s0 = byCode.get("S0");
    SectorFeature s1 = byCode.get("S1");
    SectorFeature s2 = byCode.get("S2");
    SectorFeature s3 = byCode.get("S3");

    // 기준일의 KOSPI 5·20·60일 수익률은 0 → rs = 업종 지수 수익률
    assertThat(s0.rs5()).isCloseTo(Math.pow(1.005, 5) - 1, within(1e-6));
    assertThat(s0.rs20()).isCloseTo(Math.pow(1.005, 20) - 1, within(1e-6));
    assertThat(s0.rs60()).isCloseTo(Math.pow(1.005, 60) - 1, within(1e-6));
    assertThat(s2.rs5()).isCloseTo(1000.0 / 950 - 1, within(1e-6));
    assertThat(s2.rs20()).isCloseTo(1000.0 / 920 - 1, within(1e-6));
    assertThat(s2.rs60()).isCloseTo(1000.0 / 1200 - 1, within(1e-6));
    assertThat(s1.rs60()).isNegative();
    assertThat(s3.rs5()).isPositive();

    assertThat(s0.mom()).isCloseTo(8.0 / 9, within(1e-9));
    assertThat(s2.mom()).isCloseTo(6.0 / 9, within(1e-9));
    assertThat(s3.mom()).isCloseTo(4.0 / 9, within(1e-9));
    assertThat(s1.mom()).as("세 구간 모두 최하위 → 백분위 0").isCloseTo(0.0, within(1e-9));

    assertThat(s0.consistent()).isTrue();
    assertThat(s3.consistent()).as("완만해도 세 구간 모두 초과면 consistent").isTrue();
    assertThat(s2.consistent()).as("단기 반등(rs60 < 0)은 지속이 아니다").isFalse();
    assertThat(s1.consistent()).isFalse();

    // 앞 7열(MV 기준)은 v5 그대로
    assertThat(top).allMatch(s -> s.members() == 10 && s.cw5d() != null && s.rising() != null);
  }

  @Test
  @DisplayName("overheated = 업종 지수 5일 수익률 > overheated-sigma × σ_5d(KOSPI): 기본 2.0 이면 S2(+5.3%) 만, 1.0 으로 낮추면 S0(+2.5%) 도 과열")
  void overheatedUsesSigmaMultiple() {
    MarketFeatures f = features.features(BASE);
    double sigma = f.sigma5d().get("0001");
    // 보충 구간의 ±1% 교차 30일 + 상수 30일 → σ_5d ≈ 0.016: S0 5일 수익률(0.0253)이 1σ 와 2σ 사이에 놓여 배수 검증이 성립한다
    assertThat(sigma).isBetween(0.0127, 0.0252);
    Map<String, Boolean> overheated = new java.util.HashMap<>();
    f.topSectors().forEach(s -> overheated.put(s.code(), s.overheated()));
    assertThat(overheated).containsEntry("S2", true).containsEntry("S0", false).containsEntry("S1", false).containsEntry("S3", false);

    properties.getAdvise().setOverheatedSigma(1.0);
    Map<String, Boolean> looser = new java.util.HashMap<>();
    features.features(BASE).topSectors().forEach(s -> looser.put(s.code(), s.overheated()));
    assertThat(looser).containsEntry("S2", true).containsEntry("S0", true).containsEntry("S1", false).containsEntry("S3", false);
  }

  @Test
  @DisplayName("스크리닝 후보의 secRs5/20/60 은 소속 업종 지수의 같은 값이고 secCons 는 S0·S3 1, S1·S2 0")
  void candidateSectorFeaturesFollowIndustryIndex() {
    ScreeningResult result = screening.screen(BASE);
    assertThat(result.candidates()).isNotEmpty();
    Map<String, SectorFeature> sectors = new java.util.HashMap<>();
    features.features(BASE).topSectors().forEach(s -> sectors.put(s.code(), s));
    for (CandidateRow c : result.candidates()) {
      SectorFeature s = sectors.get(c.sectorCode());
      assertThat((Double) c.features().get("secRs5")).isCloseTo(s.rs5(), within(1e-6));
      assertThat((Double) c.features().get("secRs20")).isCloseTo(s.rs20(), within(1e-6));
      assertThat((Double) c.features().get("secRs60")).isCloseTo(s.rs60(), within(1e-6));
      assertThat(AdvicePromptBuilder.secCons(c.features())).isEqualTo(s.consistent() ? 1 : 0);
      assertThat(c.signals().get("SECTOR_MOM_60D").raw()).isCloseTo(s.rs60(), within(1e-6));
    }
    assertThat(result.candidates()).extracting(CandidateRow::sectorCode).contains("S0", "S1");
  }

  @Test
  @DisplayName("업종 지수가 없는 섹터: rs·mom null, consistent false, top 정렬에서 맨 뒤(mom NULLS LAST) — 다른 섹터의 백분위는 그 섹터를 빼고 매긴다")
  void missingIndexYieldsNullRs() {
    List<Object[]> removed = jdbc.query("SELECT trade_date, close_price FROM tb_stock_index_daily WHERE index_code = 'S3' ORDER BY trade_date",
        (rs, i) -> new Object[] {rs.getObject("trade_date", LocalDate.class), rs.getBigDecimal("close_price")});
    jdbc.update("DELETE FROM tb_stock_index_daily WHERE index_code = 'S3'");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    try {
      MarketFeatures f = features.features(BASE);
      assertThat(f.topSectors()).extracting(SectorFeature::code).containsExactly("S0", "S2", "S1", "S3");
      SectorFeature s3 = f.topSectors().getLast();
      assertThat(s3.rs5()).isNull();
      assertThat(s3.rs60()).isNull();
      assertThat(s3.mom()).isNull();
      assertThat(s3.consistent()).isFalse();
      assertThat(s3.overheated()).isFalse();
      // 3섹터 백분위((rank−1)/2): rs5 S1<S0<S2, rs20 S1<S2<S0, rs60 S1<S2<S0 → S0 (0.5+1+1)/3, S2 (1+0.5+0.5)/3, S1 0
      assertThat(f.topSectors().getFirst().mom()).isCloseTo(2.5 / 3, within(1e-9));
      assertThat(f.topSectors().get(1).mom()).isCloseTo(2.0 / 3, within(1e-9));
      assertThat(f.topSectors().get(2).mom()).isCloseTo(0.0, within(1e-9));
      assertThat(f.sectorIndexAsOf()).as("다른 업종 지수는 남아 있다").isEqualTo(BASE);

      // 후보 쪽도 같은 정의: S3 후보는 secRs* 키가 없고 secCons null
      ScreeningResult result = screening.screen(BASE);
      List<CandidateRow> s3Candidates = result.candidates().stream().filter(c -> c.sectorCode().equals("S3")).toList();
      assertThat(s3Candidates).isNotEmpty().allMatch(c -> !c.features().containsKey("secRs60") && AdvicePromptBuilder.secCons(c.features()) == null);
    } finally {
      for (Object[] row : removed) {
        jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES ('S3', ?, ?, ?, ?, ?)",
            row[0], row[1], row[1], row[1], row[1]);
      }
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    }
  }

  @Test
  @DisplayName("업종 지수가 0001 보다 뒤처지면(기준일 행 없음) rs·mom 은 null·consistent false 로 남고 dataAsOf.sectorIndex 가 그 날짜를 드러낸다")
  void laggedIndustryIndexYieldsNullRs() {
    LocalDate lagged = DATES.get(DATES.size() - 2);
    List<Object[]> removed = jdbc.query("SELECT index_code, close_price FROM tb_stock_index_daily WHERE trade_date = ? AND index_code IN ('S0','S1','S2','S3')",
        (rs, i) -> new Object[] {rs.getString("index_code"), rs.getBigDecimal("close_price")}, BASE);
    assertThat(removed).hasSize(4);
    jdbc.update("DELETE FROM tb_stock_index_daily WHERE trade_date = ? AND index_code IN ('S0','S1','S2','S3')", BASE);
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    try {
      MarketFeatures f = features.features(BASE);
      assertThat(f.sectorIndexAsOf()).as("업종 지수 마지막 날 = 기준일 직전 영업일").isEqualTo(lagged);
      assertThat(f.dataAsOf()).containsEntry("sectorIndex", lagged.toString()).containsEntry("domestic", BASE.toString());
      assertThat(f.topSectors()).hasSize(4)
          .allMatch(s -> s.rs5() == null && s.rs20() == null && s.rs60() == null && s.mom() == null && !s.consistent() && !s.overheated());
      assertThat(f.topSectors()).as("mom 이 전부 null 이면 cw5(동률) → 코드 순").extracting(SectorFeature::code).containsExactly("S0", "S1", "S2", "S3");
      // 후보 쪽(feat CTE 는 같은 날짜 조인)도 전부 null → secCons null
      ScreeningResult result = screening.screen(BASE);
      assertThat(result.candidates()).isNotEmpty()
          .allMatch(c -> !c.features().containsKey("secRs5") && AdvicePromptBuilder.secCons(c.features()) == null);
    } finally {
      for (Object[] row : removed) {
        jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES (?, ?, ?, ?, ?, ?)",
            row[0], BASE, row[1], row[1], row[1], row[1]);
      }
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    }
  }

  /** 추가 섹터 X0~X6 의 일일 기울기 — S0~S3 과 섞여 11섹터가 되게. 순수 지수함수라 세 구간 순위가 같고, S2(계단) 만 구간별 순위가 다르다 */
  static final double[] EXTRA_SLOPES = {0.0042, -0.0045, 0.0030, -0.0035, 0.0020, -0.0025, -0.0015};

  @Test
  @DisplayName("섹터 11개: top 8 은 mom 내림차순, bottom 3 은 나머지에서 mom 오름차순(가장 약한 섹터가 먼저) — S1 < X1 < X3")
  void bottomThreeFromRestByAscendingMom() {
    List<String> extraTickers = new ArrayList<>();
    try {
      for (int x = 0; x < EXTRA_SLOPES.length; x++) {
        String sector = "X" + x;
        jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES (?, ?)", sector, "업종" + sector);
        for (int j = 0; j < EXTRA.size(); j++) {
          insertIndex(sector, EXTRA.get(j), 1000 * Math.pow(1 + EXTRA_SLOPES[x], j - EXTRA.size()));
        }
        for (int k = 0; k < DATES.size(); k++) {
          insertIndex(sector, DATES.get(k), 1000 * Math.pow(1 + EXTRA_SLOPES[x], k));
        }
        for (int m = 0; m < 5; m++) {
          String t = sector + "T" + m;
          extraTickers.add(t);
          jdbc.update("INSERT INTO tb_stock_master (ticker, stock_name, market_type, security_group, created_at, updated_at) VALUES (?, ?, 'KOSPI', 'ST', NOW(), NOW())",
              t, "종목" + t);
          jdbc.update("INSERT INTO tb_stock_sector_map (ticker, sector_code, valid_from, sector_name, source) VALUES (?, ?, ?, ?, 'KRX')",
              t, sector, DATES.getFirst(), "섹터" + sector);
          for (LocalDate d : DATES) {
            AdvisorSyntheticData.insertPrice(jdbc, t, d, 100);
          }
        }
      }
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_sector_daily");

      MarketFeatures f = features.features(BASE);
      // 백분위(11섹터, (rank−1)/10) 합: S0 29 · X0 26 · X2 22 · S2 21 · X4 19 · S3 16 · X6 13 · X5 10 · X3 6 · X1 3 · S1 0
      assertThat(f.topSectors()).extracting(SectorFeature::code).containsExactly("S0", "X0", "X2", "S2", "X4", "S3", "X6", "X5");
      assertThat(f.bottomSectors()).extracting(SectorFeature::code).containsExactly("S1", "X1", "X3");
      List<Double> bottomMom = f.bottomSectors().stream().map(SectorFeature::mom).toList();
      assertThat(bottomMom).isSorted();
      assertThat(bottomMom.getLast()).isLessThan(f.topSectors().getLast().mom());
      assertThat(f.bottomSectors().getFirst().mom()).isCloseTo(0.0, within(1e-9));
      assertThat(f.topSectors()).allMatch(s -> s.members() >= 5);
      assertThat(f.bottomSectors()).as("S1 은 원래 섹터(10), X* 는 추가 섹터(5) — 전부 members 게이트 통과·세 구간 미달").allMatch(s -> s.members() >= 5 && !s.consistent());
    } finally {
      jdbc.update("DELETE FROM tb_stock_index_daily WHERE index_code LIKE 'X%'");
      jdbc.update("DELETE FROM tb_stock_index_master WHERE index_code LIKE 'X%'");
      for (String t : extraTickers) {
        jdbc.update("DELETE FROM tb_stock_daily_price WHERE ticker = ?", t);
        jdbc.update("DELETE FROM tb_stock_sector_map WHERE ticker = ?", t);
        jdbc.update("DELETE FROM tb_stock_master WHERE ticker = ?", t);
      }
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
      jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_sector_daily");
    }
  }

  private void insertIndex(String code, LocalDate date, double close) {
    java.math.BigDecimal price = java.math.BigDecimal.valueOf(close).setScale(4, java.math.RoundingMode.HALF_UP);
    jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES (?, ?, ?, ?, ?, ?)",
        code, date, price, price, price, price);
  }

  @Test
  @DisplayName("bottom 정렬은 mom 오름차순(null 은 뒤) → cw5 오름차순 → 코드: 첫 원소가 가장 약한 섹터")
  void bottomOrderComparator() {
    SectorFeature a = sector("A", 0.3, 0.2);
    SectorFeature b = sector("B", 0.3, -0.1);
    SectorFeature c = sector("C", null, -5.0);
    SectorFeature d = sector("D", 0.1, 9.0);
    List<SectorFeature> list = new ArrayList<>(List.of(a, b, c, d));
    list.sort(MarketFeatureService.BOTTOM_ORDER);
    assertThat(list).extracting(SectorFeature::code).containsExactly("D", "B", "A", "C");
  }

  private static SectorFeature sector(String code, Double mom, Double cw5) {
    return new SectorFeature(code, code, cw5, null, null, null, 10, null, null, null, mom, false, false);
  }
}

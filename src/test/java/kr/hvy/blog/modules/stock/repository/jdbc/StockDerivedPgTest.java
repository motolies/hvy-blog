package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.model.AdjustEventRow;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.InvestorDailyRow;
import kr.hvy.blog.modules.stock.domain.model.ValuationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 파생 계층(db/stock-derived.sql) — 수정계수 MV·수정주가 뷰 — 와 Phase 2 writer 를 실제 PostgreSQL 로 검증한다.
 * EXP(SUM(LN)) 곱셈 정확도와 "효력일 이전에만 곱한다" 규칙이 핵심이다.
 */
@Testcontainers
class StockDerivedPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static {
    POSTGRES.withInitScripts("db/stock-schema.sql", "db/stock-derived.sql");
  }

  private static final String TICKER = "005930";
  private static final LocalDate SPLIT_DATE = LocalDate.of(2018, 5, 4);

  private JdbcTemplate jdbc;
  private BatchUpsertSupport support;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    support = new BatchUpsertSupport(jdbc);
    jdbc.update("TRUNCATE tb_stock_daily_price, tb_stock_adjust_event, tb_stock_corporate_action, tb_stock_investor_daily, tb_stock_valuation_daily");
  }

  @Test
  @DisplayName("수정계수 MV: 효력일 이전 거래일에만 계수가 곱해지고, 두 이벤트는 EXP(SUM(LN)) 로 정확히 곱해진다")
  void adjustFactorView() {
    StockDailyPriceWriter priceWriter = new StockDailyPriceWriter(support);
    List<DailyPriceRow> prices = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      LocalDate date = SPLIT_DATE.minusDays(5).plusDays(i); // 04-29 ~ 05-08 (분할일 05-04 포함)
      BigDecimal close = date.isBefore(SPLIT_DATE) ? new BigDecimal("2650000") : new BigDecimal("53000");
      prices.add(new DailyPriceRow(TICKER, date, close, close, close, close, date.isBefore(SPLIT_DATE) ? 100L : 5000L,
          1L, null, null, null, null, null, "N", null));
    }
    prices.add(new DailyPriceRow("000660", SPLIT_DATE, new BigDecimal("80000"), new BigDecimal("80000"),
        new BigDecimal("80000"), new BigDecimal("80000"), 10L, 1L, null, null, null, null, null, "N", null));
    priceWriter.upsert(prices);

    AdjustEventWriter eventWriter = new AdjustEventWriter(jdbc, support);
    eventWriter.upsert(List.of(
        new AdjustEventRow(TICKER, SPLIT_DATE, CorporateActionType.SPLIT, new BigDecimal("0.02"), new BigDecimal("50")),
        new AdjustEventRow(TICKER, SPLIT_DATE.minusDays(3), CorporateActionType.BONUS_ISSUE, new BigDecimal("0.5"), new BigDecimal("2"))));
    DerivedViewRefresher refresher = new DerivedViewRefresher(jdbc);
    assertThat(refresher.materializedViewExists(DerivedViewRefresher.MV_ADJUST_FACTOR)).isTrue();
    assertThat(refresher.viewExists(DerivedViewRefresher.VW_PRICE_ADJ)).isTrue();
    refresher.refresh(DerivedViewRefresher.MV_ADJUST_FACTOR, true);

    List<DerivedViewRefresher.AdjustedClose> rows = refresher.adjustedCloses(TICKER, SPLIT_DATE.minusDays(5), SPLIT_DATE.plusDays(4));
    assertThat(rows).hasSize(10);
    // 무상증자(05-01) 이전: 0.02 × 0.5 = 0.01 → 2,650,000 → 26,500
    assertThat(rows.get(0).adjClose().doubleValue()).isCloseTo(26_500d, within(1e-6));
    assertThat(rows.get(0).adjVolume().doubleValue()).isCloseTo(10_000d, within(1e-6)); // 100 × 50 × 2
    // 무상증자 효력일 05-01 ~ 분할 전 05-03: 0.02 만
    DerivedViewRefresher.AdjustedClose beforeSplit = rows.stream().filter(r -> r.tradeDate().equals(SPLIT_DATE.minusDays(1))).findFirst().orElseThrow();
    assertThat(beforeSplit.adjClose().doubleValue()).isCloseTo(53_000d, within(1e-6));
    // 분할일 이후: 계수 1
    DerivedViewRefresher.AdjustedClose after = rows.stream().filter(r -> r.tradeDate().equals(SPLIT_DATE)).findFirst().orElseThrow();
    assertThat(after.adjClose().doubleValue()).isCloseTo(53_000d, within(1e-6));
    assertThat(after.rawClose()).isEqualByComparingTo("53000");
    // 이벤트 없는 종목은 MV 에 없고 뷰에서는 원주가 그대로
    Integer mvOther = jdbc.queryForObject("SELECT COUNT(*) FROM mv_stock_adjust_factor WHERE ticker = '000660'", Integer.class);
    assertThat(mvOther).isZero();
    assertThat(refresher.adjustedCloses("000660", SPLIT_DATE, SPLIT_DATE).get(0).adjClose().doubleValue()).isCloseTo(80_000d, within(1e-6));

    // 계수 변경 시 verified 초기화, 동일 계수 재적재는 0건
    assertThat(eventWriter.markVerified(TICKER, true)).isEqualTo(2);
    assertThat(eventWriter.upsert(List.of(new AdjustEventRow(TICKER, SPLIT_DATE, CorporateActionType.SPLIT, new BigDecimal("0.02"), new BigDecimal("50"))))).isZero();
    assertThat(eventWriter.upsert(List.of(new AdjustEventRow(TICKER, SPLIT_DATE, CorporateActionType.SPLIT, new BigDecimal("0.02"), new BigDecimal("51"))))).isEqualTo(1);
    Boolean verified = jdbc.queryForObject("SELECT verified FROM tb_stock_adjust_event WHERE ticker = ? AND action_type = 'SPLIT'", Boolean.class, TICKER);
    assertThat(verified).isFalse();
    assertThat(eventWriter.tickersWithEvents(10)).containsExactly(TICKER);
  }

  @Test
  @DisplayName("기업행사 원본은 JSONB 로 저장되고 출처·유형으로 조회된다")
  void corporateActions() {
    CorporateActionWriter writer = new CorporateActionWriter(jdbc, support);
    CorporateActionRow split = new CorporateActionRow(TICKER, SPLIT_DATE, CorporateActionType.SPLIT, new BigDecimal("5000"),
        new BigDecimal("100"), null, CorporateActionSource.KSD, "{\"list_dt\":\"20180504\",\"sht_cd\":\"005930\"}");
    CorporateActionRow hint = new CorporateActionRow(TICKER, SPLIT_DATE, CorporateActionType.CHART_HINT, null,
        new BigDecimal("50"), null, CorporateActionSource.CHART_HINT, "{\"mod_yn\":\"Y\"}");
    assertThat(writer.upsert(List.of(split, hint))).isEqualTo(2);
    assertThat(writer.upsert(List.of(split, hint))).isZero();

    List<CorporateActionRow> ksd = writer.find(CorporateActionSource.KSD, List.of(CorporateActionType.SPLIT, CorporateActionType.BONUS_ISSUE));
    assertThat(ksd).hasSize(1);
    assertThat(ksd.get(0).rawJson()).contains("20180504");
    assertThat(writer.findByTicker(TICKER)).hasSize(2);
    String jsonType = jdbc.queryForObject(
        "SELECT raw_json->>'sht_cd' FROM tb_stock_corporate_action WHERE source = 'KSD'", String.class);
    assertThat(jsonType).isEqualTo("005930");
  }

  @Test
  @DisplayName("수급·밸류에이션 writer 는 동일값 재적재를 건너뛴다")
  void investorAndValuation() {
    StockInvestorWriter investorWriter = new StockInvestorWriter(support);
    List<InvestorDailyRow> investor = List.of(new InvestorDailyRow(TICKER, SPLIT_DATE, 1_000L, -500L, -400L, 100L, -200L, 10L, -5L, -4L));
    assertThat(investorWriter.upsert(investor)).isEqualTo(1);
    assertThat(investorWriter.upsert(investor)).isZero();

    StockValuationWriter valuationWriter = new StockValuationWriter(support);
    List<ValuationRow> valuation = List.of(new ValuationRow(TICKER, SPLIT_DATE, 400_000_000_000_000L, 5_969_782_550L,
        new BigDecimal("12.34"), new BigDecimal("1.23"), new BigDecimal("5000"), new BigDecimal("50000"),
        new BigDecimal("80000"), new BigDecimal("50000"), new BigDecimal("52.1")));
    assertThat(valuationWriter.upsert(valuation)).isEqualTo(1);
    assertThat(valuationWriter.upsert(valuation)).isZero();
    Long cap = jdbc.queryForObject("SELECT market_cap FROM tb_stock_valuation_daily WHERE ticker = ?", Long.class, TICKER);
    assertThat(cap).isEqualTo(400_000_000_000_000L);
  }
}

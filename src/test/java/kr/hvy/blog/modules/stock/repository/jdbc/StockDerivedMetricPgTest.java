package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
import kr.hvy.blog.modules.stock.domain.model.InvestorDailyRow;
import kr.hvy.blog.modules.stock.domain.model.MarketStatRows;
import kr.hvy.blog.modules.stock.domain.model.SectorMapRow;
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
 * 지표(tb_stock_daily_metric 증분 재계산 / mv_stock_index_metric / mv_stock_sector_daily)·유니버스 뷰·시장 통계 writer 를
 * 실제 PostgreSQL 로 검증한다. 윈도우 프레임 경계(ROWS BETWEEN n PRECEDING)가 핵심이다.
 */
@Testcontainers
class StockDerivedMetricPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static {
    POSTGRES.withInitScripts("db/stock-schema.sql", "db/stock-derived.sql");
  }

  private static final LocalDate START = LocalDate.of(2026, 1, 5);
  private static final int DAYS = 130;

  private JdbcTemplate jdbc;
  private BatchUpsertSupport support;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    support = new BatchUpsertSupport(jdbc);
    jdbc.update("TRUNCATE tb_stock_daily_price, tb_stock_investor_daily, tb_stock_sector_map, tb_stock_master, "
        + "tb_stock_index_daily, tb_stock_valuation_daily, tb_stock_adjust_event, tb_stock_market_stat_daily, tb_stock_daily_metric");
  }

  private static List<LocalDate> tradingDays() {
    List<LocalDate> days = new ArrayList<>();
    LocalDate d = START;
    while (days.size() < DAYS) {
      if (d.getDayOfWeek().getValue() <= 5) {
        days.add(d);
      }
      d = d.plusDays(1);
    }
    return days;
  }

  @Test
  @DisplayName("종목 지표 테이블: 수익률·이동평균·52주 고점·거래대금 비율·외인 5일 누적이 프레임대로 계산되고, 증분 재계산은 하한 이후 행만 바꾼다")
  void dailyMetric() {
    List<LocalDate> days = tradingDays();
    List<DailyPriceRow> prices = new ArrayList<>();
    List<InvestorDailyRow> investors = new ArrayList<>();
    for (int i = 0; i < days.size(); i++) {
      BigDecimal close = BigDecimal.valueOf(1000 + i); // 1000, 1001, ... 선형 상승
      prices.add(new DailyPriceRow("005930", days.get(i), close, close.add(BigDecimal.TEN), close, close, 100L + i,
          1_000_000L * (i + 1), null, null, null, null, null, "N", null));
      investors.add(new InvestorDailyRow("005930", days.get(i), 10L, -5L, -5L, 0L, 0L, null, null, null));
    }
    new StockDailyPriceWriter(support).upsert(prices);
    new StockInvestorWriter(support).upsert(investors);
    DerivedViewRefresher refresher = new DerivedViewRefresher(jdbc);
    assertThat(refresher.tableExists(DerivedViewRefresher.TB_DAILY_METRIC)).isTrue();
    assertThat(refresher.recomputeDailyMetric(null, null, "64MB", 0)).isEqualTo(DAYS);
    assertThat(refresher.recomputeDailyMetric(null, null, null, null)).isZero(); // 같은 값은 건너뜀

    LocalDate last = days.get(DAYS - 1);
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tb_stock_daily_metric WHERE ticker = ? AND trade_date = ?", "005930", last);
    double close = 1000 + DAYS - 1; // 1129
    assertThat((Double) row.get("ret_1d")).isCloseTo(close / (close - 1) - 1, within(1e-9));
    assertThat((Double) row.get("ret_120d")).isCloseTo(close / (close - 120) - 1, within(1e-9));
    assertThat((Double) row.get("ma_5")).isCloseTo(close - 2, within(1e-9)); // 최근 5개 평균
    assertThat((Double) row.get("ma_120")).isCloseTo(close - 59.5, within(1e-9));
    assertThat((Double) row.get("high_52w")).isCloseTo(close + 10, within(1e-9)); // 최근 252행 이내 최고가
    assertThat((Double) row.get("dist_high_52w")).isCloseTo(close / (close + 10) - 1, within(1e-9));
    assertThat((Double) row.get("tv_ratio_5_60")).isGreaterThan(1.0); // 거래대금 증가 추세
    assertThat((Double) row.get("foreign_net_5d")).isCloseTo(50d, within(1e-9));
    assertThat((Double) row.get("institution_net_5d")).isCloseTo(-25d, within(1e-9));

    Map<String, Object> first = jdbc.queryForMap("SELECT * FROM tb_stock_daily_metric WHERE ticker = ? AND trade_date = ?", "005930", START);
    assertThat(first.get("ret_1d")).isNull(); // 이전 행 없음
    assertThat((Double) first.get("ma_5")).isCloseTo(1000d, within(1e-9)); // 프레임이 잘려도 평균은 존재

    // 증분: 마지막 날 종가를 바꾸고 최근 10일만 다시 계산 → 하한 이후 행만 갱신되고 이전 행은 그대로
    LocalDate from = days.get(DAYS - 10);
    jdbc.update("UPDATE tb_stock_daily_price SET close_price = 2000 WHERE ticker = '005930' AND trade_date = ?", last);
    java.time.OffsetDateTime beforeAt = jdbc.queryForObject(
        "SELECT computed_at FROM tb_stock_daily_metric WHERE ticker = '005930' AND trade_date = ?", java.time.OffsetDateTime.class, START);
    int changed = refresher.recomputeDailyMetric(from, from.minusDays(400), null, null);
    assertThat(changed).isBetween(1, 10);
    Map<String, Object> updated = jdbc.queryForMap("SELECT * FROM tb_stock_daily_metric WHERE ticker = ? AND trade_date = ?", "005930", last);
    assertThat((Double) updated.get("adj_close")).isCloseTo(2000d, within(1e-9));
    assertThat((Double) updated.get("ret_1d")).isCloseTo(2000d / (close - 1) - 1, within(1e-9)); // 하한 앞 입력(lookback)으로 프레임이 이어진다
    java.time.OffsetDateTime afterAt = jdbc.queryForObject(
        "SELECT computed_at FROM tb_stock_daily_metric WHERE ticker = '005930' AND trade_date = ?", java.time.OffsetDateTime.class, START);
    assertThat(afterAt).isEqualTo(beforeAt);
  }

  @Test
  @DisplayName("지수 지표·섹터 집계·유니버스 뷰가 함께 동작한다")
  void indexSectorUniverse() {
    List<LocalDate> days = tradingDays().subList(0, 30);
    List<IndexDailyRow> index = new ArrayList<>();
    List<DailyPriceRow> prices = new ArrayList<>();
    for (int i = 0; i < days.size(); i++) {
      BigDecimal v = BigDecimal.valueOf(2500 + i);
      index.add(new IndexDailyRow("0001", days.get(i), v, v, v, v, 1L, 1L, null));
      for (String ticker : List.of("005930", "000660", "035420")) {
        BigDecimal close = BigDecimal.valueOf(10_000 + i * (ticker.equals("035420") ? -10 : 10));
        prices.add(new DailyPriceRow(ticker, days.get(i), close, close, close, close, 1000L, 5_000_000_000L, null, null,
            ticker.equals("035420") ? new BigDecimal("-1.0") : new BigDecimal("1.0"), null, null, "N", null));
      }
    }
    new MarketIndexWriter(support, jdbc).upsert(index);
    new StockDailyPriceWriter(support).upsert(prices);
    jdbc.update("INSERT INTO tb_stock_master (ticker, stock_name, market_type, security_group, created_at, updated_at) VALUES "
        + "('005930','삼성전자','KOSPI','ST',NOW(),NOW()), ('000660','SK하이닉스','KOSPI','ST',NOW(),NOW()), ('035420','NAVER','KOSPI','ST',NOW(),NOW())");
    new StockSectorMapWriter(jdbc, support).sync(List.of(
        new SectorMapRow("005930", "0021", START, "전기전자", "KRX"),
        new SectorMapRow("000660", "0021", START, "전기전자", "KRX"),
        new SectorMapRow("035420", "0046", START, "서비스업", "KRX")), START, "KRX");
    LocalDate last = days.get(days.size() - 1);
    jdbc.update("INSERT INTO tb_stock_valuation_daily (ticker, trade_date, market_cap) VALUES ('005930', ?, 400000000000000), "
        + "('000660', ?, 100000000000000), ('035420', ?, 50000000000)", last, last, last); // NAVER 시총 500억 → 유니버스 제외

    DerivedViewRefresher refresher = new DerivedViewRefresher(jdbc);
    refresher.refresh("mv_stock_adjust_factor", true);
    refresher.recomputeDailyMetric(null, null, null, null);
    for (String mv : List.of("mv_stock_index_metric", "mv_stock_sector_daily")) {
      refresher.refresh(mv, true);
    }

    Map<String, Object> idx = jdbc.queryForMap("SELECT * FROM mv_stock_index_metric WHERE index_code = '0001' AND trade_date = ?", last);
    assertThat((Double) idx.get("ret_20d")).isCloseTo(2529d / 2509d - 1, within(1e-9));
    assertThat((Double) idx.get("ma_20")).isCloseTo(2519.5, within(1e-9));

    Map<String, Object> sector = jdbc.queryForMap("SELECT * FROM mv_stock_sector_daily WHERE sector_code = '0021' AND trade_date = ?", last);
    assertThat(((Number) sector.get("member_count")).intValue()).isEqualTo(2);
    assertThat(((Number) sector.get("sum_market_cap")).longValue()).isEqualTo(500_000_000_000_000L);
    assertThat((Double) sector.get("rising_ratio")).isCloseTo(1.0, within(1e-9));
    assertThat((Double) sector.get("near_high_ratio")).isCloseTo(1.0, within(1e-9)); // 상승 종목은 신고가 근처
    Map<String, Object> service = jdbc.queryForMap("SELECT * FROM mv_stock_sector_daily WHERE sector_code = '0046' AND trade_date = ?", last);
    assertThat((Double) service.get("rising_ratio")).isCloseTo(0.0, within(1e-9));

    List<String> universe = jdbc.query("SELECT ticker FROM vw_stock_universe_daily WHERE trade_date = ? ORDER BY ticker",
        (rs, i) -> rs.getString(1), last);
    assertThat(universe).containsExactly("000660", "005930");
    Integer universeHistory = jdbc.queryForObject("SELECT COUNT(*) FROM vw_stock_universe_daily WHERE trade_date = ?", Integer.class, START);
    assertThat(universeHistory).isEqualTo(3); // 시총 스냅샷이 없는 과거는 거래대금 하한만 적용
  }

  @Test
  @DisplayName("시장 통계 writer 는 출처별 컬럼만 갱신하고 다른 출처 값을 보존한다")
  void marketStatPartialUpsert() {
    StockMarketStatWriter writer = new StockMarketStatWriter(support);
    assertThat(writer.upsertShortSale(List.of(new MarketStatRows.ShortSale("005930", START, 1000L, 70_000_000L, new BigDecimal("3.2"))))).isEqualTo(1);
    assertThat(writer.upsertCreditBalance(List.of(new MarketStatRows.CreditBalance("005930", START, 5_000L, 350_000_000L, new BigDecimal("0.12"), 10L)))).isEqualTo(1);
    assertThat(writer.upsertProgramTrade(List.of(new MarketStatRows.ProgramTrade("005930", START, -200L, -14_000_000L)))).isEqualTo(1);
    assertThat(writer.upsertShortSale(List.of(new MarketStatRows.ShortSale("005930", START, 1000L, 70_000_000L, new BigDecimal("3.2"))))).isZero();

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tb_stock_market_stat_daily WHERE ticker = '005930'");
    assertThat(((Number) row.get("short_sale_qty")).longValue()).isEqualTo(1000L);
    assertThat(((Number) row.get("credit_loan_qty")).longValue()).isEqualTo(5000L);
    assertThat(((Number) row.get("program_net_qty")).longValue()).isEqualTo(-200L);
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_market_stat_daily", Integer.class);
    assertThat(count).isEqualTo(1);
  }
}

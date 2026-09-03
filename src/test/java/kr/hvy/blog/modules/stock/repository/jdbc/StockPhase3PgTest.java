package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.stock.application.service.CollectNotifier;
import kr.hvy.blog.modules.stock.application.service.CollectValidationService;
import kr.hvy.blog.modules.stock.application.service.FinancialRowMapper;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import kr.hvy.blog.modules.stock.domain.model.GlobalMarketRow;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
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
 * Phase 3: 재무 리비전 누적, 해외 일봉 writer, 정합성 검증 SQL 을 실제 PostgreSQL 로 검증한다.
 */
@Testcontainers
class StockPhase3PgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static {
    POSTGRES.withInitScripts("db/stock-schema.sql", "db/stock-derived.sql", "db/stock-seed.sql");
  }

  private static final LocalDate D1 = LocalDate.of(2026, 9, 2);
  private static final LocalDate D2 = LocalDate.of(2026, 9, 3);

  private JdbcTemplate jdbc;
  private BatchUpsertSupport support;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    support = new BatchUpsertSupport(jdbc);
    jdbc.update("TRUNCATE tb_stock_financial, tb_stock_global_market_daily, tb_stock_daily_price, tb_stock_master, "
        + "tb_stock_index_daily, tb_stock_valuation_daily, tb_stock_corporate_action, tb_stock_adjust_event");
  }

  @Test
  @DisplayName("재무: 신규 seq 0, 같은 값은 건너뛰고, 값이 바뀌면 seq+1 로 누적된다")
  void financialRevisions() {
    StockFinancialWriter writer = new StockFinancialWriter(jdbc);
    List<FinancialRow> v1 = FinancialRowMapper.merge("005930", true,
        List.of(java.util.Map.of("stac_yymm", "202406", "sale_account", "740650", "thtr_ntin", "98413")),
        List.of(java.util.Map.of("stac_yymm", "202406", "total_aset", "4850000")), List.of());
    assertThat(writer.apply("005930", v1)).isEqualTo(1);
    assertThat(writer.apply("005930", v1)).isZero();

    List<FinancialRow> v2 = FinancialRowMapper.merge("005930", true,
        List.of(java.util.Map.of("stac_yymm", "202406", "sale_account", "740651", "thtr_ntin", "98413")),
        List.of(java.util.Map.of("stac_yymm", "202406", "total_aset", "4850000")), List.of());
    assertThat(writer.apply("005930", v2)).isEqualTo(1);
    assertThat(writer.count("005930")).isEqualTo(2);
    Integer maxSeq = jdbc.queryForObject("SELECT MAX(revision_seq) FROM tb_stock_financial WHERE ticker = '005930'", Integer.class);
    assertThat(maxSeq).isEqualTo(1);
    LocalDate available = jdbc.queryForObject(
        "SELECT available_from FROM tb_stock_financial WHERE ticker = '005930' AND revision_seq = 1", LocalDate.class);
    assertThat(available).isEqualTo(LocalDate.of(2024, 6, 30).plusDays(45));
    String raw = jdbc.queryForObject("SELECT raw_json->>'sale_account' FROM tb_stock_financial WHERE revision_seq = 1", String.class);
    assertThat(raw).isEqualTo("740651");
  }

  @Test
  @DisplayName("해외 일봉 writer 와 섹터-글로벌 시드가 적용된다")
  void globalMarket() {
    GlobalMarketWriter writer = new GlobalMarketWriter(support);
    List<GlobalMarketRow> rows = List.of(
        new GlobalMarketRow("NVDA", D1, "EQ", "NAS", new BigDecimal("120.5"), new BigDecimal("125.1"), new BigDecimal("119.8"),
            new BigDecimal("124.3"), 300_000_000L, new BigDecimal("2.15")),
        new GlobalMarketRow(".DJI", D1, "N", null, null, null, null, new BigDecimal("41000.12"), null, null));
    assertThat(writer.upsert(rows)).isEqualTo(2);
    assertThat(writer.upsert(rows)).isZero();
    Integer seeded = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_global_sector_map WHERE sector_code = 'SEMICON'", Integer.class);
    assertThat(seeded).isEqualTo(9);
  }

  @Test
  @DisplayName("정합성 검증: 결측·이상치·OHLC 위반·행 수 차이를 찾아낸다")
  void validation() {
    jdbc.update("INSERT INTO tb_stock_master (ticker, stock_name, market_type, security_group, is_active, is_suspended, created_at, updated_at) VALUES "
        + "('005930','삼성전자','KOSPI','ST',TRUE,FALSE,NOW(),NOW()), ('000660','SK하이닉스','KOSPI','ST',TRUE,FALSE,NOW(),NOW()), "
        + "('035420','NAVER','KOSPI','ST',TRUE,FALSE,NOW(),NOW()), ('000020','동화약품','KOSPI','ST',TRUE,TRUE,NOW(),NOW())");
    new MarketIndexWriter(support).upsert(List.of(
        new IndexDailyRow("0001", D1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null),
        new IndexDailyRow("0001", D2, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null)));
    StockDailyPriceWriter priceWriter = new StockDailyPriceWriter(support);
    priceWriter.upsert(List.of(
        price("005930", D1, "70000", "71000", "69000", "70500", null),
        price("000660", D1, "200000", "201000", "199000", "200500", null),
        price("035420", D1, "150000", "151000", "149000", "150500", null),
        price("005930", D2, "70500", "72000", "70000", "71500", "1.4"),
        // 000660: D2 결측 (거래정지 아님) → missing
        // 035420: 이상 등락률 + OHLC 위반 (high < close)
        price("035420", D2, "150500", "151000", "149000", "200000", "32.9")));

    KisProperties properties = new KisProperties();
    CollectValidationService service = new CollectValidationService(jdbc, properties, new CollectNotifier(Optional.empty()));
    CollectValidationService.ValidationReport report = service.inspect(D2);

    assertThat(report.previousTradeDate()).isEqualTo(D1);
    assertThat(report.activeCount()).isEqualTo(3); // 거래정지 제외
    assertThat(report.priceCount()).isEqualTo(2);
    assertThat(report.rowCountWarning()).isTrue();
    assertThat(report.missing()).containsExactly("000660");
    assertThat(report.outliers()).hasSize(1).first().asString().startsWith("035420");
    assertThat(report.ohlcViolations()).containsExactly("035420");
    assertThat(report.week52Mismatch()).isEmpty();
    assertThat(report.problems()).isEqualTo(3);
    assertThat(report.summary()).contains("결측 1");
  }

  private static DailyPriceRow price(String ticker, LocalDate date, String open, String high, String low, String close, String rate) {
    return new DailyPriceRow(ticker, date, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
        1000L, 1_000_000L, null, null, rate == null ? null : new BigDecimal(rate), null, null, "N", null);
  }
}

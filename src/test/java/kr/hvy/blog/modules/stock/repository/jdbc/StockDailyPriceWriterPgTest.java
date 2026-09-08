package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 PostgreSQL 로 검증하는 스키마·upsert 테스트 (Spring 컨텍스트 미사용).
 * <p>
 * H2(MySQL 모드)에서는 ON CONFLICT, IS DISTINCT FROM, 부분 유니크 인덱스가 검증되지 않는다.
 * 충돌 컬럼을 잘못 쓰면 데이터가 조용히 중복되고 단위 테스트로는 잡히지 않으므로 여기서 실제 DDL 을 적용해 확인한다.
 * Docker 소켓은 colima 사용 시 DOCKER_HOST 로 지정해야 한다(testcontainers-colima-socket 메모).
 */
@Testcontainers
class StockDailyPriceWriterPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static {
    // Testcontainers 2.x 신규 패키지(org.testcontainers.postgresql) 컨테이너에 DDL 초기화 스크립트를 건다
    POSTGRES.withInitScript("db/stock-schema.sql");
  }

  private JdbcTemplate jdbc;
  private StockDailyPriceWriter writer;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    writer = new StockDailyPriceWriter(new BatchUpsertSupport(jdbc));
    jdbc.update("TRUNCATE tb_stock_daily_price");
    jdbc.update("TRUNCATE tb_stock_collect_run");
  }

  @Test
  @DisplayName("스키마 초기화 스크립트가 tb_stock_ 접두 테이블 21개를 만든다 (다른 접두사는 없다)")
  void schemaCreatesAllTables() {
    Integer stockTables = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
            + "AND table_name LIKE 'tb\\_stock\\_%'",
        Integer.class);
    Integer allTables = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
        Integer.class);
    assertThat(stockTables).isEqualTo(21);
    assertThat(allTables).as("stock 모듈 테이블은 모두 tb_stock_ 접두사를 쓴다").isEqualTo(stockTables);
  }

  @Test
  @DisplayName("upsert 는 삽입 → 동일값 재적재 시 0건 → 변경분만 갱신한다")
  void upsertInsertsSkipsUnchangedUpdatesChanged() {
    List<DailyPriceRow> rows = rows("005930", 250);

    assertThat(writer.upsert(rows)).isEqualTo(250);
    assertThat(writer.upsert(rows)).isZero(); // IS DISTINCT FROM 으로 UPDATE 생략

    List<DailyPriceRow> changed = new ArrayList<>(rows.subList(0, 10).stream()
        .map(r -> new DailyPriceRow(r.ticker(), r.tradeDate(), r.open(), r.high(), r.low(),
            r.close().add(BigDecimal.ONE), r.volume(), r.tradingValue(), r.prevDiff(), r.prevDiffSign(),
            r.changeRate(), r.flngClsCode(), r.splitRate(), r.modYn(), r.revalReason()))
        .toList());
    changed.addAll(rows.subList(10, 250));
    assertThat(writer.upsert(changed)).isEqualTo(10);

    Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_daily_price", Integer.class);
    assertThat(total).isEqualTo(250);
    BigDecimal close = jdbc.queryForObject(
        "SELECT close_price FROM tb_stock_daily_price WHERE ticker = ? AND trade_date = ?",
        BigDecimal.class, "005930", rows.get(0).tradeDate());
    assertThat(close).isEqualByComparingTo(rows.get(0).close().add(BigDecimal.ONE));
  }

  @Test
  @DisplayName("기본 청크(1,000행)를 넘는 배치도 전부 적재된다")
  void upsertChunksLargeBatches() {
    List<DailyPriceRow> rows = new ArrayList<>();
    rows.addAll(rows("000660", 1500));
    rows.addAll(rows("035420", 1000));
    assertThat(writer.upsert(rows)).isEqualTo(2500);
    assertThat(BatchUpsertSupport.chunkSize(StockDailyPriceWriter.PARAMS_PER_ROW)).isEqualTo(1000);
    assertThat(BatchUpsertSupport.chunkSize(200)).isEqualTo(325); // (65535-500)/200
  }

  @Test
  @DisplayName("RUNNING 부분 유니크 인덱스가 같은 잡의 중복 실행을 막는다")
  void runningPartialUniqueIndexBlocksDuplicate() {
    String insert = "INSERT INTO tb_stock_collect_run (job_type, trigger_type, status, created_at, updated_at) "
        + "VALUES (?, 'API', ?, NOW(), NOW())";
    jdbc.update(insert, "PRICE_BACKFILL", "RUNNING");

    assertThatThrownBy(() -> jdbc.update(insert, "PRICE_BACKFILL", "RUNNING"))
        .isInstanceOf(DuplicateKeyException.class);

    // 다른 잡의 RUNNING, 같은 잡의 종료 상태는 허용된다
    jdbc.update(insert, "INDEX_BACKFILL", "RUNNING");
    jdbc.update(insert, "PRICE_BACKFILL", "SUCCESS");
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_collect_run", Integer.class);
    assertThat(count).isEqualTo(3);
  }

  private static List<DailyPriceRow> rows(String ticker, int count) {
    List<DailyPriceRow> rows = new ArrayList<>(count);
    LocalDate date = LocalDate.of(2015, 1, 2);
    for (int i = 0; i < count; i++) {
      BigDecimal base = BigDecimal.valueOf(50_000 + i);
      rows.add(new DailyPriceRow(ticker, date.plusDays(i), base, base.add(BigDecimal.valueOf(500)),
          base.subtract(BigDecimal.valueOf(500)), base.add(BigDecimal.valueOf(100)),
          1_000_000L + i, 50_000_000_000L + i, BigDecimal.valueOf(100), "2", new BigDecimal("0.2000"),
          null, null, "N", null));
    }
    return rows;
  }
}

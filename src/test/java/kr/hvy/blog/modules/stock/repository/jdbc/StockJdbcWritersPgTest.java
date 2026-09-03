package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.masterfile.IndexCodeRecord;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.model.HolidayRow;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
import kr.hvy.blog.modules.stock.domain.model.MasterHistoryRow;
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
 * SCD2·섹터맵·지수마스터·지수/휴장일 writer 를 실제 PostgreSQL 로 검증한다 (Spring 컨텍스트 미사용).
 */
@Testcontainers
class StockJdbcWritersPgTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  static {
    POSTGRES.withInitScript("db/stock-schema.sql");
  }

  private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
  private static final LocalDate D2 = LocalDate.of(2026, 9, 2);

  private JdbcTemplate jdbc;
  private BatchUpsertSupport support;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    support = new BatchUpsertSupport(jdbc);
    jdbc.update("TRUNCATE tb_stock_master_history, tb_stock_sector_map, tb_stock_index_master, tb_stock_index_daily, tb_stock_market_holiday");
  }

  private static MasterHistoryRow history(String ticker, LocalDate from, boolean suspended) {
    return MasterHistoryRow.of(ticker, from, "삼성전자", MarketType.KOSPI, "ST", "0021", true, true, suspended, false, true, 100L);
  }

  @Test
  @DisplayName("SCD2: 바뀐 종목만 현재 행을 닫고 새 행을 넣으며, 같은 날 재실행은 제자리 갱신한다")
  void masterHistoryScd2() {
    StockMasterHistoryWriter writer = new StockMasterHistoryWriter(jdbc, support);
    assertThat(writer.apply(List.of(history("005930", D1, false), history("000660", D1, false)))).isEqualTo(2);
    Map<String, String> hashes = writer.currentHashes();
    assertThat(hashes).hasSize(2);

    // 다음 날 005930 만 거래정지로 바뀜
    MasterHistoryRow changed = history("005930", D2, true);
    assertThat(changed.snapshotHash()).isNotEqualTo(hashes.get("005930"));
    assertThat(writer.apply(List.of(changed))).isEqualTo(1);

    assertThat(writer.countCurrent()).isEqualTo(2);
    LocalDate closed = jdbc.queryForObject(
        "SELECT valid_to FROM tb_stock_master_history WHERE ticker = '005930' AND valid_from = ?", LocalDate.class, D1);
    assertThat(closed).isEqualTo(D2);
    assertThat(writer.currentHashes().get("005930")).isEqualTo(changed.snapshotHash());

    // 같은 날 다시 바뀌면 (ticker, valid_from) 충돌을 제자리 갱신으로 흡수
    MasterHistoryRow again = history("005930", D2, false);
    writer.apply(List.of(again));
    Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_master_history WHERE ticker = '005930'", Integer.class);
    assertThat(rows).isEqualTo(2);
    assertThat(writer.currentHashes().get("005930")).isEqualTo(again.snapshotHash());
  }

  @Test
  @DisplayName("섹터맵 동기화: 변경 종목은 닫고 새 행, 사라진 종목은 닫기만, 같은 날 변경은 삭제 후 삽입")
  void sectorMapSync() {
    StockSectorMapWriter writer = new StockSectorMapWriter(jdbc, support);
    List<SectorMapRow> day1 = List.of(
        new SectorMapRow("005930", "0021", D1, "전기전자", "KRX"),
        new SectorMapRow("000660", "0021", D1, "전기전자", "KRX"),
        new SectorMapRow("035420", "0046", D1, "서비스업", "KRX"));
    assertThat(writer.sync(day1, D1, "KRX")).isEqualTo(3);
    assertThat(writer.sync(day1, D1, "KRX")).isZero();

    // D2: 035420 섹터 이동, 000660 상장폐지(desired 에서 제외)
    List<SectorMapRow> day2 = List.of(
        new SectorMapRow("005930", "0021", D2, "전기전자", "KRX"),
        new SectorMapRow("035420", "0021", D2, "전기전자", "KRX"));
    assertThat(writer.sync(day2, D2, "KRX")).isEqualTo(1);
    Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_sector_map WHERE valid_to IS NULL", Integer.class);
    assertThat(open).isEqualTo(2);
    LocalDate closed = jdbc.queryForObject(
        "SELECT valid_to FROM tb_stock_sector_map WHERE ticker = '000660'", LocalDate.class);
    assertThat(closed).isEqualTo(D2);

    // 같은 날 035420 이 또 바뀌면 길이 0 구간 대신 삭제 후 삽입
    List<SectorMapRow> day2b = List.of(
        new SectorMapRow("005930", "0021", D2, "전기전자", "KRX"),
        new SectorMapRow("035420", "0046", D2, "서비스업", "KRX"));
    writer.sync(day2b, D2, "KRX");
    Integer rows035420 = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_sector_map WHERE ticker = '035420'", Integer.class);
    assertThat(rows035420).isEqualTo(2); // D1 행(닫힘) + D2 0046 행
  }

  @Test
  @DisplayName("지수 마스터는 파일에 없는 코드를 비활성으로 돌리고 활성 코드만 대상 목록에 준다")
  void indexMaster() {
    MarketIndexMasterWriter writer = new MarketIndexMasterWriter(jdbc, support);
    writer.replaceAll(List.of(new IndexCodeRecord("0", "0001", "종합"), new IndexCodeRecord("0", "0002", "대형주"),
        new IndexCodeRecord("1", "1001", "코스닥 종합")));
    assertThat(writer.activeCodes()).containsExactly("0001", "0002", "1001");

    writer.replaceAll(List.of(new IndexCodeRecord("0", "0001", "종합지수"), new IndexCodeRecord("1", "1001", "코스닥 종합")));
    assertThat(writer.activeCodes()).containsExactly("0001", "1001");
    assertThat(writer.names()).containsEntry("0001", "종합지수").containsEntry("0002", "대형주");
  }

  @Test
  @DisplayName("지수 일봉·휴장일 upsert 는 동일값 재적재를 건너뛴다")
  void indexDailyAndHoliday() {
    MarketIndexWriter indexWriter = new MarketIndexWriter(support);
    List<IndexDailyRow> rows = List.of(new IndexDailyRow("0001", D1, new BigDecimal("2600.1"), new BigDecimal("2650.5"),
        new BigDecimal("2590.0"), new BigDecimal("2640.2"), 500_000L, 9_000_000_000L, null));
    assertThat(indexWriter.upsert(rows)).isEqualTo(1);
    assertThat(indexWriter.upsert(rows)).isZero();

    MarketHolidayWriter holidayWriter = new MarketHolidayWriter(support, jdbc);
    List<HolidayRow> days = List.of(new HolidayRow(D1, true, true, true, true), new HolidayRow(D2, false, false, false, false));
    assertThat(holidayWriter.upsert(days)).isEqualTo(2);
    assertThat(holidayWriter.upsert(days)).isZero();
    assertThat(holidayWriter.findOpen(D1)).contains(true);
    assertThat(holidayWriter.findOpen(D2)).contains(false);
    assertThat(holidayWriter.findOpen(D2.plusDays(1))).isEmpty();
    assertThat(holidayWriter.maxDate()).contains(D2);
    assertThat(holidayWriter.openDays(D1, D2)).containsExactly(D1);
  }
}

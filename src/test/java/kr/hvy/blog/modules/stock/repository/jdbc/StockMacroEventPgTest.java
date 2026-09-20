package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.model.EventTimelineRow;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * tb_stock_macro_daily: 값 정정 시 observed_at·available_from 불변. tb_stock_event_timeline: LIVE 행은 재수집이 덮어쓰지 못하고 BACKFILL 만 갱신된다.
 */
@Testcontainers
class StockMacroEventPgTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  private JdbcTemplate jdbc;
  private MacroDailyWriter macroWriter;
  private EventTimelineWriter timelineWriter;

  @BeforeAll
  static void schema() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
    }
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    macroWriter = new MacroDailyWriter(new BatchUpsertSupport(jdbc), jdbc);
    timelineWriter = new EventTimelineWriter(new BatchUpsertSupport(jdbc));
    jdbc.update("TRUNCATE tb_stock_macro_daily, tb_stock_event_timeline");
  }

  @Test
  @DisplayName("거시: 같은 값 재수집은 0행, 정정은 value 만 바뀌고 observed_at·available_from 은 그대로")
  void macroCorrectionKeepsObservedAt() {
    LocalDate d = LocalDate.of(2026, 9, 18);
    MacroObservation vix = new MacroObservation(MacroSeries.VIX, d, new BigDecimal("14.81"), MacroSource.CBOE, d.plusDays(1));
    assertThat(macroWriter.upsert(List.of(vix))).isEqualTo(1);
    assertThat(macroWriter.upsert(List.of(vix))).as("같은 값은 변경 없음").isZero();
    Map<String, Object> before = jdbc.queryForMap("SELECT observed_at, available_from FROM tb_stock_macro_daily WHERE series_code = 'VIX'");

    MacroObservation corrected = new MacroObservation(MacroSeries.VIX, d, new BigDecimal("14.90"), MacroSource.CBOE, d.plusDays(5));
    assertThat(macroWriter.upsert(List.of(corrected))).isEqualTo(1);
    Map<String, Object> after = jdbc.queryForMap("SELECT value, observed_at, available_from FROM tb_stock_macro_daily WHERE series_code = 'VIX'");
    assertThat((BigDecimal) after.get("value")).isEqualByComparingTo("14.90");
    assertThat(after.get("observed_at")).isEqualTo(before.get("observed_at"));
    assertThat(after.get("available_from")).as("정정 행의 available_from 도 최초값").isEqualTo(before.get("available_from"));
    assertThat(macroWriter.latestObsDate(MacroSeries.VIX)).isEqualTo(d);
    assertThat(macroWriter.latestObsDate(MacroSeries.UST2Y)).isNull();
  }

  @Test
  @DisplayName("사건 시계열: LIVE 행은 다른 값으로 다시 넣어도 그대로, BACKFILL 은 별도 행이며 갱신된다")
  void liveRowsAreImmutable() {
    LocalDate d = LocalDate.of(2026, 9, 18);
    EventTimelineRow live = new EventTimelineRow("KR_GEO", d, EventTimelineRow.SOURCE_LIVE, 40L, 4000L, 0.01, -2.5);
    assertThat(timelineWriter.upsert(List.of(live))).isEqualTo(1);
    EventTimelineRow liveAgain = new EventTimelineRow("KR_GEO", d, EventTimelineRow.SOURCE_LIVE, 45L, 4100L, 0.011, -2.0);
    assertThat(timelineWriter.upsert(List.of(liveAgain))).as("LIVE 는 덮어쓰지 않는다").isZero();
    assertThat(jdbc.queryForObject("SELECT article_vol FROM tb_stock_event_timeline WHERE source = 'LIVE'", Long.class)).isEqualTo(40L);

    EventTimelineRow backfill = new EventTimelineRow("KR_GEO", d, EventTimelineRow.SOURCE_BACKFILL, 42L, 4050L, 0.0104, -2.4);
    assertThat(timelineWriter.upsert(List.of(backfill))).as("같은 날 BACKFILL 은 별도 행").isEqualTo(1);
    EventTimelineRow backfillAgain = new EventTimelineRow("KR_GEO", d, EventTimelineRow.SOURCE_BACKFILL, 43L, 4050L, 0.0106, -2.4);
    assertThat(timelineWriter.upsert(List.of(backfillAgain))).as("BACKFILL 은 갱신된다").isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT article_vol FROM tb_stock_event_timeline WHERE source = 'BACKFILL'", Long.class)).isEqualTo(43L);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_event_timeline", Integer.class)).isEqualTo(2);
  }
}

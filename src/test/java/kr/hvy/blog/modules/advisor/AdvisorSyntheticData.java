package kr.hvy.blog.modules.advisor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PG 테스트 공용 합성 데이터: 40종목 × 30영업일, adj_close(t) = 100 + i·t (시가=종가), 지수 2500 상수, 거래대금 20억, change_rate 0.5.
 * MOM_20D = i/40, 외국인 5일 순매수 = (i−20)·1e8, 섹터 S0~S3 (i mod 4), KOSPI/KOSDAQ 는 i 홀짝.
 * <p>
 * advice-v6: 섹터 S0~S3 의 업종 지수 행(tb_stock_index_master·tb_stock_index_daily, 지수 코드 = 섹터 코드)도 넣는다 — 기울기가 달라 시장 대비 초과(rs)
 * 의 부호가 갈린다({@link #sectorIndexClose}). 30영업일이라 ret_60d(rs60) 는 null 이다. 업종 지수 행은 vw_stock_market_calendar(0001 만)·유니버스에
 * 영향이 없으므로 non-null 경로가 필요한 테스트는 자기 컨테이너에서 {@link #insertSectorIndices} 로 더 이른 날짜(k &lt; 0)를 보충한다.
 */
public final class AdvisorSyntheticData {

  public static final int TICKERS = 40;
  public static final List<LocalDate> DATES = businessDays(LocalDate.of(2026, 8, 3), 30);
  public static final LocalDate BASE = DATES.getLast();
  /** 업종 지수 코드(= 섹터 코드). S0 꾸준한 초과, S1 꾸준한 미달, S2 단기 반등(rs5·rs20 > 0, rs60 < 0), S3 완만한 초과 */
  public static final List<String> SECTORS = List.of("S0", "S1", "S2", "S3");

  private AdvisorSyntheticData() {
  }

  /**
   * stock·advisor 스키마와 시드를 적용하고 합성 데이터를 넣는다.
   */
  public static void install(PostgreSQLContainer container) throws Exception {
    try (Connection c = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-derived.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/advisor-seed.sql"));
    }
    JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(container.getJdbcUrl(),
        container.getUsername(), container.getPassword()));
    seedStockData(jdbc);
  }

  public static void seedStockData(JdbcTemplate jdbc) {
    jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES ('0001', 'KOSPI'), ('1001', 'KOSDAQ')");
    for (String sector : SECTORS) {
      jdbc.update("INSERT INTO tb_stock_index_master (index_code, index_name) VALUES (?, ?)", sector, "업종" + sector);
    }
    for (int k = 0; k < DATES.size(); k++) {
      LocalDate d = DATES.get(k);
      jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) "
          + "VALUES ('0001', ?, 2500, 2500, 2500, 2500), ('1001', ?, 2500, 2500, 2500, 2500)", d, d);
      insertSectorIndices(jdbc, d, k);
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
    refresh(jdbc);
  }

  public static void refresh(JdbcTemplate jdbc) {
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_adjust_factor");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_index_metric");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_sector_daily");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_market_breadth_daily");
  }

  public static void insertPrice(JdbcTemplate jdbc, String ticker, LocalDate date, double close) {
    BigDecimal price = BigDecimal.valueOf(close);
    jdbc.update("INSERT INTO tb_stock_daily_price (ticker, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, change_rate) "
        + "VALUES (?, ?, ?, ?, ?, ?, 1000, 2000000000, 0.5)", ticker, date, price, price, price, price);
  }

  /**
   * 업종 지수 S0~S3 의 하루치 일봉(시가=고가=저가=종가) 을 넣는다. k 는 {@link #DATES} 첫날을 0 으로 하는 영업일 순번(음수면 그 이전 — 확장 시드).
   */
  public static void insertSectorIndices(JdbcTemplate jdbc, LocalDate date, int k) {
    for (int s = 0; s < SECTORS.size(); s++) {
      BigDecimal close = BigDecimal.valueOf(sectorIndexClose(s, k)).setScale(4, RoundingMode.HALF_UP);
      jdbc.update("INSERT INTO tb_stock_index_daily (index_code, trade_date, open_price, high_price, low_price, close_price) VALUES (?, ?, ?, ?, ?, ?)",
          SECTORS.get(s), date, close, close, close, close);
    }
  }

  /**
   * 업종 지수 합성 종가. k 는 DATES 첫날 = 0 인 영업일 순번, BASE 는 k = 29. KOSPI 가 2500 상수라 rs = 업종 지수 수익률 그대로다.
   * <ul>
   *   <li>S0: 1000 × 1.005^k — 5·20·60일 전부 양수(consistent)</li>
   *   <li>S1: 1000 × 0.995^k — 전부 음수</li>
   *   <li>S2: 60일 전까지 1200 → 20일 전까지 920 → 5일 전까지 950 → 1000 계단 — rs5·rs20 &gt; 0, rs60 &lt; 0 (단기 반등, 지속 아님)</li>
   *   <li>S3: 1000 × 1.001^k — 완만한 초과(consistent 이지만 mom 낮음)</li>
   * </ul>
   */
  public static double sectorIndexClose(int sector, int k) {
    int base = DATES.size() - 1;
    return switch (sector) {
      case 0 -> 1000 * Math.pow(1.005, k);
      case 1 -> 1000 * Math.pow(0.995, k);
      case 2 -> k <= base - 60 ? 1200 : k <= base - 20 ? 920 : k <= base - 5 ? 950 : 1000;
      default -> 1000 * Math.pow(1.001, k);
    };
  }

  public static String ticker(int i) {
    return String.format("T%02d", i);
  }

  /** 합성 종가 100 + i·k */
  public static double close(int i, int k) {
    return 100 + i * k;
  }

  public static List<LocalDate> businessDays(LocalDate start, int count) {
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
}

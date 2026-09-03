package kr.hvy.blog.modules.stock.repository.jdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 파생 MV(db/stock-derived.sql) 갱신·조회. MV 는 Flyway 없이 psql 로 만들므로 존재 여부를 먼저 확인한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DerivedViewRefresher {

  public static final String MV_ADJUST_FACTOR = "mv_stock_adjust_factor";
  public static final String VW_PRICE_ADJ = "vw_stock_daily_price_adj";

  private final JdbcTemplate jdbcTemplate;

  /** 수정주가 뷰 1행 */
  public record AdjustedClose(LocalDate tradeDate, BigDecimal adjClose, BigDecimal adjVolume, BigDecimal rawClose) {
  }

  /**
   * MV 존재 여부 (pg_matviews).
   */
  public boolean materializedViewExists(String name) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM pg_matviews WHERE schemaname = current_schema() AND matviewname = ?", Integer.class, name);
    return count != null && count > 0;
  }

  /**
   * MV 를 갱신한다. CONCURRENTLY 는 유니크 인덱스가 있고 한 번 채워진 MV 에만 가능하다.
   *
   * @return 소요 ms
   */
  public long refresh(String name, boolean concurrently) {
    long started = System.currentTimeMillis();
    jdbcTemplate.execute("REFRESH MATERIALIZED VIEW " + (concurrently ? "CONCURRENTLY " : "") + name);
    long elapsed = System.currentTimeMillis() - started;
    log.info("MV 갱신: {} concurrently={} {}ms", name, concurrently, elapsed);
    return elapsed;
  }

  /**
   * 수정주가 뷰에서 종목의 [from, to] 구간을 읽는다.
   */
  public List<AdjustedClose> adjustedCloses(String ticker, LocalDate from, LocalDate to) {
    return jdbcTemplate.query(
        "SELECT trade_date, adj_close, adj_volume, raw_close FROM " + VW_PRICE_ADJ
            + " WHERE ticker = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
        (rs, i) -> new AdjustedClose(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getBigDecimal(3),
            rs.getBigDecimal(4)), ticker, from, to);
  }

  /**
   * 뷰(일반 뷰) 존재 여부.
   */
  public boolean viewExists(String name) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.views WHERE table_schema = current_schema() AND table_name = ?",
        Integer.class, name);
    return count != null && count > 0;
  }
}

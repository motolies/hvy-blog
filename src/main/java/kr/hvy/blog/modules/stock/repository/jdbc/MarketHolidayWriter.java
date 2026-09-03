package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.stock.domain.model.HolidayRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 휴장일(tb_stock_market_holiday) upsert 와 조회.
 */
@Repository
@RequiredArgsConstructor
public class MarketHolidayWriter {

  static final int PARAMS_PER_ROW = 5;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_market_holiday (trade_date, is_open, is_business, is_trading, is_settlement, collected_at)
      VALUES (?, ?, ?, ?, ?, NOW())
      ON CONFLICT (trade_date) DO UPDATE SET
          is_open       = EXCLUDED.is_open,
          is_business   = EXCLUDED.is_business,
          is_trading    = EXCLUDED.is_trading,
          is_settlement = EXCLUDED.is_settlement,
          collected_at  = NOW()
      WHERE (tb_stock_market_holiday.is_open, tb_stock_market_holiday.is_business, tb_stock_market_holiday.is_trading, tb_stock_market_holiday.is_settlement)
            IS DISTINCT FROM (EXCLUDED.is_open, EXCLUDED.is_business, EXCLUDED.is_trading, EXCLUDED.is_settlement)
      """;

  private final BatchUpsertSupport upsertSupport;
  private final JdbcTemplate jdbcTemplate;

  /**
   * 휴장일 행들을 upsert 하고 변경 행 수를 돌려준다.
   */
  public int upsert(List<HolidayRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, MarketHolidayWriter::bind);
  }

  /**
   * 해당 일자의 개장 여부. 수집되지 않은 날짜면 empty.
   */
  public Optional<Boolean> findOpen(LocalDate date) {
    List<Boolean> result = jdbcTemplate.query("SELECT is_open FROM tb_stock_market_holiday WHERE trade_date = ?",
        (rs, i) -> rs.getBoolean(1), date);
    return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
  }

  /**
   * 수집된 가장 미래 일자.
   */
  public Optional<LocalDate> maxDate() {
    LocalDate max = jdbcTemplate.queryForObject("SELECT MAX(trade_date) FROM tb_stock_market_holiday", LocalDate.class);
    return Optional.ofNullable(max);
  }

  /**
   * [from, to] 구간의 개장일 목록 (오름차순).
   */
  public List<LocalDate> openDays(LocalDate from, LocalDate to) {
    return jdbcTemplate.query(
        "SELECT trade_date FROM tb_stock_market_holiday WHERE is_open = TRUE AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
        (rs, i) -> rs.getObject(1, LocalDate.class), from, to);
  }

  private static void bind(PreparedStatement ps, HolidayRow row) throws SQLException {
    ps.setObject(1, row.tradeDate());
    ps.setBoolean(2, row.isOpen());
    ps.setBoolean(3, row.isBusiness());
    ps.setBoolean(4, row.isTrading());
    ps.setBoolean(5, row.isSettlement());
  }
}

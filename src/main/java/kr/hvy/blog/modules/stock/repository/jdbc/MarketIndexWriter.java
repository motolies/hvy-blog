package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 시장·업종 지수 일봉(tb_stock_index_daily) 배치 upsert.
 */
@Repository
@RequiredArgsConstructor
public class MarketIndexWriter {

  static final int PARAMS_PER_ROW = 9;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_index_daily
          (index_code, trade_date, open_price, high_price, low_price, close_price,
           volume, trading_value, change_rate, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (index_code, trade_date) DO UPDATE SET
          open_price    = EXCLUDED.open_price,
          high_price    = EXCLUDED.high_price,
          low_price     = EXCLUDED.low_price,
          close_price   = EXCLUDED.close_price,
          volume        = EXCLUDED.volume,
          trading_value = EXCLUDED.trading_value,
          change_rate   = EXCLUDED.change_rate,
          collected_at  = NOW()
      WHERE tb_stock_index_daily.open_price    IS DISTINCT FROM EXCLUDED.open_price
         OR tb_stock_index_daily.high_price    IS DISTINCT FROM EXCLUDED.high_price
         OR tb_stock_index_daily.low_price     IS DISTINCT FROM EXCLUDED.low_price
         OR tb_stock_index_daily.close_price   IS DISTINCT FROM EXCLUDED.close_price
         OR tb_stock_index_daily.volume        IS DISTINCT FROM EXCLUDED.volume
         OR tb_stock_index_daily.trading_value IS DISTINCT FROM EXCLUDED.trading_value
      """;

  private final BatchUpsertSupport upsertSupport;
  private final JdbcTemplate jdbcTemplate;

  /**
   * 지수 일봉이 존재하는 날짜(내림차순). 0001 을 넘기면 영업일 정본이다 (vw_stock_market_calendar 와 같은 정의).
   */
  public List<LocalDate> tradeDates(String indexCode, LocalDate from, LocalDate to) {
    return jdbcTemplate.query(
        "SELECT trade_date FROM tb_stock_index_daily WHERE index_code = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date DESC",
        (rs, i) -> rs.getObject(1, LocalDate.class), indexCode, from, to);
  }

  /**
   * 지수 일봉 행들을 upsert 하고 실제 변경 행 수를 돌려준다.
   */
  public int upsert(List<IndexDailyRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, MarketIndexWriter::bind);
  }

  private static void bind(PreparedStatement ps, IndexDailyRow row) throws SQLException {
    ps.setString(1, row.indexCode());
    ps.setObject(2, row.tradeDate());
    ps.setBigDecimal(3, row.open());
    ps.setBigDecimal(4, row.high());
    ps.setBigDecimal(5, row.low());
    ps.setBigDecimal(6, row.close());
    ps.setObject(7, row.volume());
    ps.setObject(8, row.tradingValue());
    ps.setBigDecimal(9, row.changeRate());
  }
}

package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.EtfNavRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * ETF NAV 일별(tb_stock_etf_nav_daily) 배치 upsert. 값이 같은 행은 IS DISTINCT FROM 으로 UPDATE 를 건너뛴다.
 */
@Repository
@RequiredArgsConstructor
public class StockEtfNavWriter {

  static final int PARAMS_PER_ROW = 13;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_etf_nav_daily
          (ticker, trade_date, close_price, prev_diff, prev_diff_sign, change_rate, volume,
           nav, nav_prev_diff, nav_prev_diff_sign, nav_change_rate, nav_diff, disparity_rate, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          close_price        = EXCLUDED.close_price,
          prev_diff          = EXCLUDED.prev_diff,
          prev_diff_sign     = EXCLUDED.prev_diff_sign,
          change_rate        = EXCLUDED.change_rate,
          volume             = EXCLUDED.volume,
          nav                = EXCLUDED.nav,
          nav_prev_diff      = EXCLUDED.nav_prev_diff,
          nav_prev_diff_sign = EXCLUDED.nav_prev_diff_sign,
          nav_change_rate    = EXCLUDED.nav_change_rate,
          nav_diff           = EXCLUDED.nav_diff,
          disparity_rate     = EXCLUDED.disparity_rate,
          collected_at       = NOW()
      WHERE tb_stock_etf_nav_daily.close_price     IS DISTINCT FROM EXCLUDED.close_price
         OR tb_stock_etf_nav_daily.volume          IS DISTINCT FROM EXCLUDED.volume
         OR tb_stock_etf_nav_daily.nav             IS DISTINCT FROM EXCLUDED.nav
         OR tb_stock_etf_nav_daily.nav_diff        IS DISTINCT FROM EXCLUDED.nav_diff
         OR tb_stock_etf_nav_daily.disparity_rate  IS DISTINCT FROM EXCLUDED.disparity_rate
         OR tb_stock_etf_nav_daily.change_rate     IS DISTINCT FROM EXCLUDED.change_rate
         OR tb_stock_etf_nav_daily.nav_change_rate IS DISTINCT FROM EXCLUDED.nav_change_rate
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * NAV 행들을 upsert 하고 실제로 삽입·변경된 행 수를 돌려준다.
   */
  public int upsert(List<EtfNavRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, StockEtfNavWriter::bind);
  }

  private static void bind(PreparedStatement ps, EtfNavRow row) throws SQLException {
    ps.setString(1, row.ticker());
    ps.setObject(2, row.tradeDate());
    ps.setBigDecimal(3, row.close());
    ps.setBigDecimal(4, row.prevDiff());
    ps.setString(5, row.prevDiffSign());
    ps.setBigDecimal(6, row.changeRate());
    ps.setObject(7, row.volume());
    ps.setBigDecimal(8, row.nav());
    ps.setBigDecimal(9, row.navPrevDiff());
    ps.setString(10, row.navPrevDiffSign());
    ps.setBigDecimal(11, row.navChangeRate());
    ps.setBigDecimal(12, row.navDiff());
    ps.setBigDecimal(13, row.disparityRate());
  }
}

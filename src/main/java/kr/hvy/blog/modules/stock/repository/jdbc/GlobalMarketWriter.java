package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.GlobalMarketRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 해외 지수·환율·ETF 일봉(tb_stock_global_market_daily) 배치 upsert.
 */
@Repository
@RequiredArgsConstructor
public class GlobalMarketWriter {

  static final int PARAMS_PER_ROW = 10;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_global_market_daily
          (symbol, trade_date, market_div, exchange, open_price, high_price, low_price, close_price, volume, change_rate, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (symbol, trade_date) DO UPDATE SET
          market_div   = EXCLUDED.market_div,
          exchange     = EXCLUDED.exchange,
          open_price   = EXCLUDED.open_price,
          high_price   = EXCLUDED.high_price,
          low_price    = EXCLUDED.low_price,
          close_price  = EXCLUDED.close_price,
          volume       = EXCLUDED.volume,
          change_rate  = EXCLUDED.change_rate,
          collected_at = NOW()
      WHERE (tb_stock_global_market_daily.open_price, tb_stock_global_market_daily.high_price, tb_stock_global_market_daily.low_price,
             tb_stock_global_market_daily.close_price, tb_stock_global_market_daily.volume, tb_stock_global_market_daily.change_rate)
            IS DISTINCT FROM
            (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price, EXCLUDED.close_price, EXCLUDED.volume, EXCLUDED.change_rate)
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * 해외 일봉 행들을 upsert 하고 변경 행 수를 돌려준다.
   */
  public int upsert(List<GlobalMarketRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, GlobalMarketWriter::bind);
  }

  private static void bind(PreparedStatement ps, GlobalMarketRow row) throws SQLException {
    ps.setString(1, row.symbol());
    ps.setObject(2, row.tradeDate());
    ps.setString(3, row.marketDiv());
    ps.setString(4, row.exchange());
    ps.setBigDecimal(5, row.open());
    ps.setBigDecimal(6, row.high());
    ps.setBigDecimal(7, row.low());
    ps.setBigDecimal(8, row.close());
    ps.setObject(9, row.volume());
    ps.setBigDecimal(10, row.changeRate());
  }
}

package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.ValuationRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 밸류에이션 일별 스냅샷(tb_stock_valuation_daily) 배치 upsert. 같은 날 재실행은 최신 값으로 덮어쓴다.
 */
@Repository
@RequiredArgsConstructor
public class StockValuationWriter {

  static final int PARAMS_PER_ROW = 11;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_valuation_daily
          (ticker, trade_date, market_cap, listed_shares, per, pbr, eps, bps, week52_high, week52_low, foreign_hold_rate, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          market_cap        = EXCLUDED.market_cap,
          listed_shares     = EXCLUDED.listed_shares,
          per               = EXCLUDED.per,
          pbr               = EXCLUDED.pbr,
          eps               = EXCLUDED.eps,
          bps               = EXCLUDED.bps,
          week52_high       = EXCLUDED.week52_high,
          week52_low        = EXCLUDED.week52_low,
          foreign_hold_rate = EXCLUDED.foreign_hold_rate,
          collected_at      = NOW()
      WHERE (tb_stock_valuation_daily.market_cap, tb_stock_valuation_daily.listed_shares, tb_stock_valuation_daily.per,
             tb_stock_valuation_daily.pbr, tb_stock_valuation_daily.eps, tb_stock_valuation_daily.bps,
             tb_stock_valuation_daily.week52_high, tb_stock_valuation_daily.week52_low, tb_stock_valuation_daily.foreign_hold_rate)
            IS DISTINCT FROM
            (EXCLUDED.market_cap, EXCLUDED.listed_shares, EXCLUDED.per, EXCLUDED.pbr, EXCLUDED.eps, EXCLUDED.bps,
             EXCLUDED.week52_high, EXCLUDED.week52_low, EXCLUDED.foreign_hold_rate)
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * 스냅샷 행들을 upsert 하고 변경 행 수를 돌려준다.
   */
  public int upsert(List<ValuationRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, StockValuationWriter::bind);
  }

  private static void bind(PreparedStatement ps, ValuationRow row) throws SQLException {
    ps.setString(1, row.ticker());
    ps.setObject(2, row.tradeDate());
    ps.setObject(3, row.marketCap());
    ps.setObject(4, row.listedShares());
    ps.setBigDecimal(5, row.per());
    ps.setBigDecimal(6, row.pbr());
    ps.setBigDecimal(7, row.eps());
    ps.setBigDecimal(8, row.bps());
    ps.setBigDecimal(9, row.week52High());
    ps.setBigDecimal(10, row.week52Low());
    ps.setBigDecimal(11, row.foreignHoldRate());
  }
}

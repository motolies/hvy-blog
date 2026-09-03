package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.InvestorDailyRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 투자자별 일별 순매수(tb_stock_investor_daily) 배치 upsert.
 */
@Repository
@RequiredArgsConstructor
public class StockInvestorWriter {

  static final int PARAMS_PER_ROW = 10;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_investor_daily
          (ticker, trade_date, foreign_net_amt, institution_net_amt, individual_net_amt, pension_net_amt, other_net_amt,
           foreign_net_qty, institution_net_qty, individual_net_qty, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          foreign_net_amt     = EXCLUDED.foreign_net_amt,
          institution_net_amt = EXCLUDED.institution_net_amt,
          individual_net_amt  = EXCLUDED.individual_net_amt,
          pension_net_amt     = EXCLUDED.pension_net_amt,
          other_net_amt       = EXCLUDED.other_net_amt,
          foreign_net_qty     = EXCLUDED.foreign_net_qty,
          institution_net_qty = EXCLUDED.institution_net_qty,
          individual_net_qty  = EXCLUDED.individual_net_qty,
          collected_at        = NOW()
      WHERE (tb_stock_investor_daily.foreign_net_amt, tb_stock_investor_daily.institution_net_amt,
             tb_stock_investor_daily.individual_net_amt, tb_stock_investor_daily.pension_net_amt,
             tb_stock_investor_daily.other_net_amt, tb_stock_investor_daily.foreign_net_qty,
             tb_stock_investor_daily.institution_net_qty, tb_stock_investor_daily.individual_net_qty)
            IS DISTINCT FROM
            (EXCLUDED.foreign_net_amt, EXCLUDED.institution_net_amt, EXCLUDED.individual_net_amt, EXCLUDED.pension_net_amt,
             EXCLUDED.other_net_amt, EXCLUDED.foreign_net_qty, EXCLUDED.institution_net_qty, EXCLUDED.individual_net_qty)
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * 수급 행들을 upsert 하고 변경 행 수를 돌려준다.
   */
  public int upsert(List<InvestorDailyRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, StockInvestorWriter::bind);
  }

  private static void bind(PreparedStatement ps, InvestorDailyRow row) throws SQLException {
    ps.setString(1, row.ticker());
    ps.setObject(2, row.tradeDate());
    ps.setLong(3, row.foreignNetAmt());
    ps.setLong(4, row.institutionNetAmt());
    ps.setLong(5, row.individualNetAmt());
    ps.setLong(6, row.pensionNetAmt());
    ps.setLong(7, row.otherNetAmt());
    ps.setObject(8, row.foreignNetQty());
    ps.setObject(9, row.institutionNetQty());
    ps.setObject(10, row.individualNetQty());
  }
}

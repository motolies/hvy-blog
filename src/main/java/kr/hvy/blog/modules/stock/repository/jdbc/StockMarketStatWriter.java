package kr.hvy.blog.modules.stock.repository.jdbc;

import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.MarketStatRows;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 시장 통계(tb_stock_market_stat_daily) 출처별 부분 upsert. 다른 출처의 컬럼은 건드리지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class StockMarketStatWriter {

  private static final String SHORT_SALE_SQL = """
      INSERT INTO tb_stock_market_stat_daily (ticker, trade_date, short_sale_qty, short_sale_amt, short_sale_ratio, collected_at)
      VALUES (?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          short_sale_qty   = EXCLUDED.short_sale_qty,
          short_sale_amt   = EXCLUDED.short_sale_amt,
          short_sale_ratio = EXCLUDED.short_sale_ratio,
          collected_at     = NOW()
      WHERE (tb_stock_market_stat_daily.short_sale_qty, tb_stock_market_stat_daily.short_sale_amt, tb_stock_market_stat_daily.short_sale_ratio)
            IS DISTINCT FROM (EXCLUDED.short_sale_qty, EXCLUDED.short_sale_amt, EXCLUDED.short_sale_ratio)
      """;

  private static final String CREDIT_SQL = """
      INSERT INTO tb_stock_market_stat_daily (ticker, trade_date, credit_loan_qty, credit_loan_amt, credit_loan_ratio, stock_loan_qty, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          credit_loan_qty   = EXCLUDED.credit_loan_qty,
          credit_loan_amt   = EXCLUDED.credit_loan_amt,
          credit_loan_ratio = EXCLUDED.credit_loan_ratio,
          stock_loan_qty    = EXCLUDED.stock_loan_qty,
          collected_at      = NOW()
      WHERE (tb_stock_market_stat_daily.credit_loan_qty, tb_stock_market_stat_daily.credit_loan_amt,
             tb_stock_market_stat_daily.credit_loan_ratio, tb_stock_market_stat_daily.stock_loan_qty)
            IS DISTINCT FROM (EXCLUDED.credit_loan_qty, EXCLUDED.credit_loan_amt, EXCLUDED.credit_loan_ratio, EXCLUDED.stock_loan_qty)
      """;

  private static final String PROGRAM_SQL = """
      INSERT INTO tb_stock_market_stat_daily (ticker, trade_date, program_net_qty, program_net_amt, collected_at)
      VALUES (?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          program_net_qty = EXCLUDED.program_net_qty,
          program_net_amt = EXCLUDED.program_net_amt,
          collected_at    = NOW()
      WHERE (tb_stock_market_stat_daily.program_net_qty, tb_stock_market_stat_daily.program_net_amt)
            IS DISTINCT FROM (EXCLUDED.program_net_qty, EXCLUDED.program_net_amt)
      """;

  private final BatchUpsertSupport upsertSupport;

  public int upsertShortSale(List<MarketStatRows.ShortSale> rows) {
    return upsertSupport.batchUpsert(SHORT_SALE_SQL, rows, 5, (ps, r) -> {
      ps.setString(1, r.ticker());
      ps.setObject(2, r.tradeDate());
      ps.setObject(3, r.qty());
      ps.setObject(4, r.amt());
      ps.setBigDecimal(5, r.volumeRatio());
    });
  }

  public int upsertCreditBalance(List<MarketStatRows.CreditBalance> rows) {
    return upsertSupport.batchUpsert(CREDIT_SQL, rows, 6, (ps, r) -> {
      ps.setString(1, r.ticker());
      ps.setObject(2, r.tradeDate());
      ps.setObject(3, r.loanQty());
      ps.setObject(4, r.loanAmt());
      ps.setBigDecimal(5, r.loanRatio());
      ps.setObject(6, r.stockLoanQty());
    });
  }

  public int upsertProgramTrade(List<MarketStatRows.ProgramTrade> rows) {
    return upsertSupport.batchUpsert(PROGRAM_SQL, rows, 4, (ps, r) -> {
      ps.setString(1, r.ticker());
      ps.setObject(2, r.tradeDate());
      ps.setObject(3, r.netQty());
      ps.setObject(4, r.netAmt());
    });
  }
}

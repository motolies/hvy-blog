package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.MarketInvestorRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 시장별 투자자 일별(tb_stock_market_investor_daily) 배치 upsert. 값이 같은 행은 IS DISTINCT FROM 으로 UPDATE 를 건너뛴다.
 */
@Repository
@RequiredArgsConstructor
public class StockMarketInvestorWriter {

  static final int PARAMS_PER_ROW = 32;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_market_investor_daily
          (market_type, trade_date, foreign_net_amt, foreign_net_qty, foreign_reg_net_amt, foreign_reg_net_qty, foreign_nreg_net_amt, foreign_nreg_net_qty, individual_net_amt, individual_net_qty, institution_net_amt, institution_net_qty, securities_net_amt, securities_net_qty, invest_trust_net_amt, invest_trust_net_qty, private_fund_net_amt, private_fund_net_qty, bank_net_amt, bank_net_qty, insurance_net_amt, insurance_net_qty, merchant_bank_net_amt, merchant_bank_net_qty, pension_net_amt, pension_net_qty, other_net_amt, other_net_qty, other_org_net_amt, other_org_net_qty, other_corp_net_amt, other_corp_net_qty, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (market_type, trade_date) DO UPDATE SET
          foreign_net_amt          = EXCLUDED.foreign_net_amt,
          foreign_net_qty          = EXCLUDED.foreign_net_qty,
          foreign_reg_net_amt      = EXCLUDED.foreign_reg_net_amt,
          foreign_reg_net_qty      = EXCLUDED.foreign_reg_net_qty,
          foreign_nreg_net_amt     = EXCLUDED.foreign_nreg_net_amt,
          foreign_nreg_net_qty     = EXCLUDED.foreign_nreg_net_qty,
          individual_net_amt       = EXCLUDED.individual_net_amt,
          individual_net_qty       = EXCLUDED.individual_net_qty,
          institution_net_amt      = EXCLUDED.institution_net_amt,
          institution_net_qty      = EXCLUDED.institution_net_qty,
          securities_net_amt       = EXCLUDED.securities_net_amt,
          securities_net_qty       = EXCLUDED.securities_net_qty,
          invest_trust_net_amt     = EXCLUDED.invest_trust_net_amt,
          invest_trust_net_qty     = EXCLUDED.invest_trust_net_qty,
          private_fund_net_amt     = EXCLUDED.private_fund_net_amt,
          private_fund_net_qty     = EXCLUDED.private_fund_net_qty,
          bank_net_amt             = EXCLUDED.bank_net_amt,
          bank_net_qty             = EXCLUDED.bank_net_qty,
          insurance_net_amt        = EXCLUDED.insurance_net_amt,
          insurance_net_qty        = EXCLUDED.insurance_net_qty,
          merchant_bank_net_amt    = EXCLUDED.merchant_bank_net_amt,
          merchant_bank_net_qty    = EXCLUDED.merchant_bank_net_qty,
          pension_net_amt          = EXCLUDED.pension_net_amt,
          pension_net_qty          = EXCLUDED.pension_net_qty,
          other_net_amt            = EXCLUDED.other_net_amt,
          other_net_qty            = EXCLUDED.other_net_qty,
          other_org_net_amt        = EXCLUDED.other_org_net_amt,
          other_org_net_qty        = EXCLUDED.other_org_net_qty,
          other_corp_net_amt       = EXCLUDED.other_corp_net_amt,
          other_corp_net_qty       = EXCLUDED.other_corp_net_qty,
          collected_at             = NOW()
      WHERE tb_stock_market_investor_daily.foreign_net_amt          IS DISTINCT FROM EXCLUDED.foreign_net_amt
         OR tb_stock_market_investor_daily.foreign_net_qty          IS DISTINCT FROM EXCLUDED.foreign_net_qty
         OR tb_stock_market_investor_daily.foreign_reg_net_amt      IS DISTINCT FROM EXCLUDED.foreign_reg_net_amt
         OR tb_stock_market_investor_daily.foreign_reg_net_qty      IS DISTINCT FROM EXCLUDED.foreign_reg_net_qty
         OR tb_stock_market_investor_daily.foreign_nreg_net_amt     IS DISTINCT FROM EXCLUDED.foreign_nreg_net_amt
         OR tb_stock_market_investor_daily.foreign_nreg_net_qty     IS DISTINCT FROM EXCLUDED.foreign_nreg_net_qty
         OR tb_stock_market_investor_daily.individual_net_amt       IS DISTINCT FROM EXCLUDED.individual_net_amt
         OR tb_stock_market_investor_daily.individual_net_qty       IS DISTINCT FROM EXCLUDED.individual_net_qty
         OR tb_stock_market_investor_daily.institution_net_amt      IS DISTINCT FROM EXCLUDED.institution_net_amt
         OR tb_stock_market_investor_daily.institution_net_qty      IS DISTINCT FROM EXCLUDED.institution_net_qty
         OR tb_stock_market_investor_daily.securities_net_amt       IS DISTINCT FROM EXCLUDED.securities_net_amt
         OR tb_stock_market_investor_daily.securities_net_qty       IS DISTINCT FROM EXCLUDED.securities_net_qty
         OR tb_stock_market_investor_daily.invest_trust_net_amt     IS DISTINCT FROM EXCLUDED.invest_trust_net_amt
         OR tb_stock_market_investor_daily.invest_trust_net_qty     IS DISTINCT FROM EXCLUDED.invest_trust_net_qty
         OR tb_stock_market_investor_daily.private_fund_net_amt     IS DISTINCT FROM EXCLUDED.private_fund_net_amt
         OR tb_stock_market_investor_daily.private_fund_net_qty     IS DISTINCT FROM EXCLUDED.private_fund_net_qty
         OR tb_stock_market_investor_daily.bank_net_amt             IS DISTINCT FROM EXCLUDED.bank_net_amt
         OR tb_stock_market_investor_daily.bank_net_qty             IS DISTINCT FROM EXCLUDED.bank_net_qty
         OR tb_stock_market_investor_daily.insurance_net_amt        IS DISTINCT FROM EXCLUDED.insurance_net_amt
         OR tb_stock_market_investor_daily.insurance_net_qty        IS DISTINCT FROM EXCLUDED.insurance_net_qty
         OR tb_stock_market_investor_daily.merchant_bank_net_amt    IS DISTINCT FROM EXCLUDED.merchant_bank_net_amt
         OR tb_stock_market_investor_daily.merchant_bank_net_qty    IS DISTINCT FROM EXCLUDED.merchant_bank_net_qty
         OR tb_stock_market_investor_daily.pension_net_amt          IS DISTINCT FROM EXCLUDED.pension_net_amt
         OR tb_stock_market_investor_daily.pension_net_qty          IS DISTINCT FROM EXCLUDED.pension_net_qty
         OR tb_stock_market_investor_daily.other_net_amt            IS DISTINCT FROM EXCLUDED.other_net_amt
         OR tb_stock_market_investor_daily.other_net_qty            IS DISTINCT FROM EXCLUDED.other_net_qty
         OR tb_stock_market_investor_daily.other_org_net_amt        IS DISTINCT FROM EXCLUDED.other_org_net_amt
         OR tb_stock_market_investor_daily.other_org_net_qty        IS DISTINCT FROM EXCLUDED.other_org_net_qty
         OR tb_stock_market_investor_daily.other_corp_net_amt       IS DISTINCT FROM EXCLUDED.other_corp_net_amt
         OR tb_stock_market_investor_daily.other_corp_net_qty       IS DISTINCT FROM EXCLUDED.other_corp_net_qty
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * 시장별 투자자 행들을 upsert 하고 실제 변경 행 수를 돌려준다.
   */
  public int upsert(List<MarketInvestorRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, StockMarketInvestorWriter::bind);
  }

  private static void bind(PreparedStatement ps, MarketInvestorRow row) throws SQLException {
    ps.setString(1, row.marketType().getCode());
    ps.setObject(2, row.tradeDate());
    ps.setObject(3, row.foreignNetAmt());
    ps.setObject(4, row.foreignNetQty());
    ps.setObject(5, row.foreignRegNetAmt());
    ps.setObject(6, row.foreignRegNetQty());
    ps.setObject(7, row.foreignNregNetAmt());
    ps.setObject(8, row.foreignNregNetQty());
    ps.setObject(9, row.individualNetAmt());
    ps.setObject(10, row.individualNetQty());
    ps.setObject(11, row.institutionNetAmt());
    ps.setObject(12, row.institutionNetQty());
    ps.setObject(13, row.securitiesNetAmt());
    ps.setObject(14, row.securitiesNetQty());
    ps.setObject(15, row.investTrustNetAmt());
    ps.setObject(16, row.investTrustNetQty());
    ps.setObject(17, row.privateFundNetAmt());
    ps.setObject(18, row.privateFundNetQty());
    ps.setObject(19, row.bankNetAmt());
    ps.setObject(20, row.bankNetQty());
    ps.setObject(21, row.insuranceNetAmt());
    ps.setObject(22, row.insuranceNetQty());
    ps.setObject(23, row.merchantBankNetAmt());
    ps.setObject(24, row.merchantBankNetQty());
    ps.setObject(25, row.pensionNetAmt());
    ps.setObject(26, row.pensionNetQty());
    ps.setObject(27, row.otherNetAmt());
    ps.setObject(28, row.otherNetQty());
    ps.setObject(29, row.otherOrgNetAmt());
    ps.setObject(30, row.otherOrgNetQty());
    ps.setObject(31, row.otherCorpNetAmt());
    ps.setObject(32, row.otherCorpNetQty());
  }
}

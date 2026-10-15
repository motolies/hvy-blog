package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.MarketType;

/**
 * tb_stock_market_investor_daily 1행: 시장(KOSPI/KOSDAQ) 단위 투자자 15주체 순매수 대금(원, KIS 백만원 ×1e6)·수량(천주, KIS 원천 그대로). 결측은 null.
 */
public record MarketInvestorRow(
    MarketType marketType,
    LocalDate tradeDate,
    Long foreignNetAmt,
    Long foreignNetQty,
    Long foreignRegNetAmt,
    Long foreignRegNetQty,
    Long foreignNregNetAmt,
    Long foreignNregNetQty,
    Long individualNetAmt,
    Long individualNetQty,
    Long institutionNetAmt,
    Long institutionNetQty,
    Long securitiesNetAmt,
    Long securitiesNetQty,
    Long investTrustNetAmt,
    Long investTrustNetQty,
    Long privateFundNetAmt,
    Long privateFundNetQty,
    Long bankNetAmt,
    Long bankNetQty,
    Long insuranceNetAmt,
    Long insuranceNetQty,
    Long merchantBankNetAmt,
    Long merchantBankNetQty,
    Long pensionNetAmt,
    Long pensionNetQty,
    Long otherNetAmt,
    Long otherNetQty,
    Long otherOrgNetAmt,
    Long otherOrgNetQty,
    Long otherCorpNetAmt,
    Long otherCorpNetQty
) {
}

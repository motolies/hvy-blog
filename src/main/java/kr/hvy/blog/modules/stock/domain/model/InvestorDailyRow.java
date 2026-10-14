package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;

/**
 * tb_stock_investor_daily 1행. 금액 단위는 원이며 null은 결측, 0은 실제 0이다. 부호가 순매수 방향이다.
 */
public record InvestorDailyRow(
    String ticker,
    LocalDate tradeDate,
    Long foreignNetAmt,
    Long institutionNetAmt,
    Long individualNetAmt,
    Long pensionNetAmt,
    Long otherNetAmt,
    Long foreignNetQty,
    Long institutionNetQty,
    Long individualNetQty
) {
}

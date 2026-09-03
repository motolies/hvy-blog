package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;

/**
 * tb_stock_investor_daily 1행. 금액 단위는 KIS 응답 그대로(원 추정, 실측 필요)이며 부호가 순매수 방향이다.
 */
public record InvestorDailyRow(
    String ticker,
    LocalDate tradeDate,
    long foreignNetAmt,
    long institutionNetAmt,
    long individualNetAmt,
    long pensionNetAmt,
    long otherNetAmt,
    Long foreignNetQty,
    Long institutionNetQty,
    Long individualNetQty
) {
}

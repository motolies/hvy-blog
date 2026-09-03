package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * tb_stock_daily_price 1행 (원주가). KIS output2 를 변환한 결과이며 JPA 엔티티가 아니다.
 */
public record DailyPriceRow(
    String ticker,
    LocalDate tradeDate,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    long tradingValue,
    BigDecimal prevDiff,
    String prevDiffSign,
    BigDecimal changeRate,
    String flngClsCode,
    BigDecimal splitRate,
    String modYn,
    String revalReason
) {
}

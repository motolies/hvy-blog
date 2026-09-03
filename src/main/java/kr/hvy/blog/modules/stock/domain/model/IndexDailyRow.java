package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * tb_stock_index_daily 1행.
 */
public record IndexDailyRow(
    String indexCode,
    LocalDate tradeDate,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    Long volume,
    Long tradingValue,
    BigDecimal changeRate
) {
}

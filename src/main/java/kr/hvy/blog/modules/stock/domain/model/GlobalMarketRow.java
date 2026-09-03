package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * tb_stock_global_market_daily 1행. trade_date 는 현지 거래일이며, 국내 D일 분석에는 미국 D-1 마감까지만 쓴다(룩어헤드 규칙).
 */
public record GlobalMarketRow(
    String symbol,
    LocalDate tradeDate,
    String marketDiv,
    String exchange,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    Long volume,
    BigDecimal changeRate
) {
}

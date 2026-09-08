package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * tb_stock_etf_nav_daily 1행. 괴리율(disparityRate) = (종가 − NAV) / NAV 의 KIS 계산값(%), navDiff = 종가 − NAV(원).
 */
public record EtfNavRow(
    String ticker,
    LocalDate tradeDate,
    BigDecimal close,
    BigDecimal prevDiff,
    String prevDiffSign,
    BigDecimal changeRate,
    Long volume,
    BigDecimal nav,
    BigDecimal navPrevDiff,
    String navPrevDiffSign,
    BigDecimal navChangeRate,
    BigDecimal navDiff,
    BigDecimal disparityRate
) {
}

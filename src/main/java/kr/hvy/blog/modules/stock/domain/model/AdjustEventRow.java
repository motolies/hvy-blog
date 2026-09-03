package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;

/**
 * tb_stock_adjust_event 1행. effective_date 이전 거래일의 가격에 price_factor, 거래량에 volume_factor 를 곱한다.
 */
public record AdjustEventRow(
    String ticker,
    LocalDate effectiveDate,
    CorporateActionType actionType,
    BigDecimal priceFactor,
    BigDecimal volumeFactor
) {
}

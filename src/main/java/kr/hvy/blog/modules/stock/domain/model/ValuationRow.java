package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * tb_stock_valuation_daily 1행. marketCap 은 원 단위(KIS hts_avls 억원 × 1e8).
 */
public record ValuationRow(
    String ticker,
    LocalDate tradeDate,
    Long marketCap,
    Long listedShares,
    BigDecimal per,
    BigDecimal pbr,
    BigDecimal eps,
    BigDecimal bps,
    BigDecimal week52High,
    BigDecimal week52Low,
    BigDecimal foreignHoldRate
) {
}

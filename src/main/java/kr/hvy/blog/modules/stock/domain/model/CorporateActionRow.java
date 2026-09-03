package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;

/**
 * tb_stock_corporate_action 1행. ratio/cash 의 의미는 유형별로 다르다:
 * SPLIT/REVERSE_SPLIT 변경 전·후 액면가, BONUS/RIGHTS 배정율(주당 주수), RIGHTS 발행가(cash),
 * CAPITAL_REDUCTION 감자배정율, DIVIDEND 주당 현금배당금(cash).
 */
public record CorporateActionRow(
    String ticker,
    LocalDate effectiveDate,
    CorporateActionType actionType,
    BigDecimal ratioBefore,
    BigDecimal ratioAfter,
    BigDecimal cashAmount,
    CorporateActionSource source,
    String rawJson
) {
}

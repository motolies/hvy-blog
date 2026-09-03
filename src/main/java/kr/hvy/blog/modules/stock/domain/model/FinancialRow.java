package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * tb_stock_financial 1행(정정 이력 전). 금액 단위는 KIS 재무 API 응답 그대로(억원 추정, 실측 필요).
 * available_from 은 룩어헤드 방지용 "이 날부터 알 수 있었다" 날짜다.
 */
public record FinancialRow(
    String ticker,
    String fiscalPeriod,
    String periodType,
    LocalDate disclosedAt,
    LocalDate availableFrom,
    String availableRule,
    Long revenue,
    Long operatingProfit,
    Long netIncome,
    Long totalAsset,
    Long totalEquity,
    Long totalDebt,
    BigDecimal roe,
    BigDecimal debtRatio,
    BigDecimal revenueGrowth,
    BigDecimal profitGrowth,
    String rawJson
) {

  public static final String PERIOD_ANNUAL = "Y";
  public static final String PERIOD_QUARTER = "Q";

  /**
   * 정정 판정용: 수치 컬럼이 모두 같으면 같은 값으로 본다 (raw_json 차이는 무시).
   */
  public boolean sameValues(FinancialRow other) {
    return other != null
        && Objects.equals(revenue, other.revenue) && Objects.equals(operatingProfit, other.operatingProfit)
        && Objects.equals(netIncome, other.netIncome) && Objects.equals(totalAsset, other.totalAsset)
        && Objects.equals(totalEquity, other.totalEquity) && Objects.equals(totalDebt, other.totalDebt)
        && compare(roe, other.roe) && compare(debtRatio, other.debtRatio)
        && compare(revenueGrowth, other.revenueGrowth) && compare(profitGrowth, other.profitGrowth);
  }

  private static boolean compare(BigDecimal a, BigDecimal b) {
    return (a == null && b == null) || (a != null && b != null && a.compareTo(b) == 0);
  }
}

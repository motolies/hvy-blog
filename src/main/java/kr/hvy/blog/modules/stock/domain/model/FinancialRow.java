package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * tb_stock_financial 1행(정정 이력 전). 금액 단위는 KIS 재무 API 응답 그대로(억원 추정, 실측 필요).
 * available_from 은 룩어헤드 방지용 "이 날부터 알 수 있었다" 날짜다.
 * <p>
 * 핵심 10개(손익·대차·재무비율)와 2026-09-08 승격한 확장 9개(성장성·수익성·안정성)로 나뉜다.
 * 확장 지표가 비어 있던 기존 행에 처음 값이 들어오는 것은 정정이 아니라 채움이다(리비전 미증가).
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
    BigDecimal netIncomeGrowth,
    BigDecimal operatingProfitGrowth,
    BigDecimal equityGrowth,
    BigDecimal assetGrowth,
    BigDecimal roa,
    BigDecimal netMargin,
    BigDecimal grossMargin,
    BigDecimal currentRatio,
    BigDecimal quickRatio,
    BigDecimal borrowingDependency,
    String rawJson
) {

  public static final String PERIOD_ANNUAL = "Y";
  public static final String PERIOD_QUARTER = "Q";

  /**
   * 정정 판정용: 수치 컬럼 19개가 모두 같으면 같은 값으로 본다 (raw_json 차이는 무시).
   */
  public boolean sameValues(FinancialRow other) {
    return sameCoreValues(other) && sameExtendedValues(other);
  }

  /**
   * 핵심 10개(손익·대차·재무비율)만 비교한다.
   */
  public boolean sameCoreValues(FinancialRow other) {
    return other != null
        && Objects.equals(revenue, other.revenue) && Objects.equals(operatingProfit, other.operatingProfit)
        && Objects.equals(netIncome, other.netIncome) && Objects.equals(totalAsset, other.totalAsset)
        && Objects.equals(totalEquity, other.totalEquity) && Objects.equals(totalDebt, other.totalDebt)
        && compare(roe, other.roe) && compare(debtRatio, other.debtRatio)
        && compare(revenueGrowth, other.revenueGrowth) && compare(netIncomeGrowth, other.netIncomeGrowth);
  }

  /**
   * 확장 9개(성장성·수익성·안정성)만 비교한다.
   */
  public boolean sameExtendedValues(FinancialRow other) {
    return other != null
        && compare(operatingProfitGrowth, other.operatingProfitGrowth) && compare(equityGrowth, other.equityGrowth)
        && compare(assetGrowth, other.assetGrowth) && compare(roa, other.roa) && compare(netMargin, other.netMargin)
        && compare(grossMargin, other.grossMargin) && compare(currentRatio, other.currentRatio)
        && compare(quickRatio, other.quickRatio) && compare(borrowingDependency, other.borrowingDependency);
  }

  /**
   * 확장 지표가 하나라도 있는지. 전부 NULL 이면 확장 API 도입 전 행이다.
   */
  public boolean hasExtendedMetrics() {
    return operatingProfitGrowth != null || equityGrowth != null || assetGrowth != null || roa != null
        || netMargin != null || grossMargin != null || currentRatio != null || quickRatio != null
        || borrowingDependency != null;
  }

  private static boolean compare(BigDecimal a, BigDecimal b) {
    return (a == null && b == null) || (a != null && b != null && a.compareTo(b) == 0);
  }
}

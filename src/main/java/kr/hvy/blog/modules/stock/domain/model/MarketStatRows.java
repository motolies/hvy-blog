package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * tb_stock_market_stat_daily 의 출처별 부분 행. 세 API 가 서로 다른 날짜 집합을 주므로 컬럼 묶음별로 upsert 한다.
 */
public final class MarketStatRows {

  private MarketStatRows() {
  }

  /** 공매도 */
  public record ShortSale(String ticker, LocalDate tradeDate, Long qty, Long amt, BigDecimal volumeRatio) {
  }

  /** 신용잔고 */
  public record CreditBalance(String ticker, LocalDate tradeDate, Long loanQty, Long loanAmt, BigDecimal loanRatio, Long stockLoanQty) {
  }

  /** 프로그램매매 */
  public record ProgramTrade(String ticker, LocalDate tradeDate, Long netQty, Long netAmt) {
  }
}

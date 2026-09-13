package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;

/**
 * 시그널 일별 rank-IC 1행 (tb_advisor_signal_ic_daily).
 */
public record SignalIcRow(String signalCode, LocalDate tradeDate, int horizonDays, double rankIc, int n) {
}

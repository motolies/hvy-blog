package kr.hvy.blog.modules.advisor.domain.model;

import lombok.Builder;

/**
 * 세트 안 시그널 가중치 1행 (tb_advisor_signal_weight).
 */
@Builder(toBuilder = true)
public record SignalWeightRow(
    String signalCode,
    double baseWeight,
    double multiplier,
    double weight,
    boolean enabled,
    Double icMean,
    Double icSe,
    Double tStat,
    Integer nDays,
    boolean flagged,
    String note) {
}

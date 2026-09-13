package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import lombok.Builder;

/**
 * 가중치 세트 (tb_advisor_weight_set + tb_advisor_signal_weight).
 */
@Builder(toBuilder = true)
public record WeightSet(
    Long weightSetId,
    LocalDate asOf,
    int windowDays,
    double nEff,
    WeightSetSource source,
    boolean active,
    String reason,
    Long runId,
    List<SignalWeightRow> weights) {

  /** 활성(enabled) 시그널의 code → weight */
  public Map<String, Double> enabledWeights() {
    return weights.stream()
        .filter(SignalWeightRow::enabled)
        .collect(Collectors.toMap(SignalWeightRow::signalCode, SignalWeightRow::weight, (a, b) -> a, java.util.LinkedHashMap::new));
  }

  public Map<String, SignalWeightRow> byCode() {
    return weights.stream().collect(Collectors.toMap(SignalWeightRow::signalCode, Function.identity(), (a, b) -> a,
        java.util.LinkedHashMap::new));
  }
}

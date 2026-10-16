package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import lombok.Builder;

/**
 * 가중치 세트 (tb_advisor_weight_set + tb_advisor_signal_weight). horizonDays 는 학습 호라이즌(5 DAILY·20 H20) — 활성 세트는 호라이즌마다 1개다(M5).
 * asOf 는 생성일이 아니라 IC 창의 마지막 기준일 d(d+h 영업일 수익률이 실현된 마지막 날)라 생성 시각(createdAt)보다 h 영업일 이상 이르다.
 * createdAt 은 조회 시에만 채워진다(저장은 DB 기본값).
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
    List<SignalWeightRow> weights,
    int horizonDays,
    Instant createdAt) {

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

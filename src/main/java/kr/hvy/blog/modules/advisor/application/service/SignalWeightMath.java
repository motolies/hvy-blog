package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;

/**
 * IC → 가중치 배수 산식 (순수 함수, 단위 테스트 대상).
 * <pre>
 * ĪC = mean(IC_d), se = std(IC_d) / √n_eff, t = ĪC / se, n_eff = n_days / horizon (5일 겹침 보정)
 * raw = ĪC / IC_ref,  m̂ = 1 + n_eff/(n_eff + prior) · (raw − 1),  m = clip(m̂, min, max)
 * weight = base · m, 이후 Σweight = Σbase 로 재정규화. 음의 IC 는 부호를 뒤집지 않고 하한 + flagged.
 * </pre>
 * 픽 적중률 EMA 를 쓰지 않는 이유: 픽은 합성 점수의 결과라 시그널별 적중률이 정의되지 않고, 하루 5~10건은 유효 표본이 너무 작다(2026-09-13 검토).
 */
public final class SignalWeightMath {

  private SignalWeightMath() {
  }

  /** 시그널 1개의 창 통계 */
  public record IcStat(String signalCode, int nDays, double nEff, double mean, double se, double tStat) {
  }

  /**
   * 창 안 IC 행을 시그널별 통계로 집계한다.
   */
  public static Map<String, IcStat> aggregate(List<SignalIcRow> rows, int horizonDays) {
    Map<String, List<Double>> byCode = new LinkedHashMap<>();
    for (SignalIcRow row : rows) {
      byCode.computeIfAbsent(row.signalCode(), k -> new ArrayList<>()).add(row.rankIc());
    }
    Map<String, IcStat> stats = new LinkedHashMap<>();
    for (Map.Entry<String, List<Double>> e : byCode.entrySet()) {
      List<Double> ics = e.getValue();
      int n = ics.size();
      double mean = ics.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      double var = n < 2 ? 0 : ics.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (n - 1);
      double nEff = (double) n / Math.max(1, horizonDays);
      double se = nEff <= 0 ? Double.NaN : Math.sqrt(var / nEff);
      double t = se > 0 ? mean / se : 0;
      stats.put(e.getKey(), new IcStat(e.getKey(), n, nEff, mean, se, t));
    }
    return stats;
  }

  /**
   * 통계로 새 가중치 행을 만든다. 학습 대상이 아니거나 통계가 없는 시그널은 배수 1.0.
   * 게이트(n_eff ≥ min) 는 호출자가 판단한다 — 여기서는 주어진 통계를 그대로 반영한다.
   */
  public static List<SignalWeightRow> apply(Map<String, IcStat> stats, List<SignalWeightRow> current, AdvisorProperties.Ic ic) {
    List<SignalWeightRow> updated = new ArrayList<>();
    double baseSum = 0;
    double weightedSum = 0;
    for (SignalWeightRow w : current) {
      SignalCode code = SignalCode.valueOf(w.signalCode());
      IcStat stat = stats.get(w.signalCode());
      double multiplier = 1.0;
      boolean flagged = false;
      Double mean = null;
      Double se = null;
      Double t = null;
      Integer nDays = null;
      String note = w.note();
      if (code.isIcLearnable() && (stat == null || stat.nDays() == 0)) {
        note = "IC 없음 — 배수 1.0 유지";
      }
      if (code.isIcLearnable() && stat != null && stat.nDays() > 0) {
        double raw = stat.mean() / ic.getReferenceIc();
        double shrink = stat.nEff() / (stat.nEff() + ic.getPriorNEff());
        double estimated = 1 + shrink * (raw - 1);
        multiplier = Math.max(ic.getMultiplierMin(), Math.min(ic.getMultiplierMax(), estimated));
        flagged = stat.mean() < 0 || stat.tStat() < -2;
        mean = stat.mean();
        se = stat.se();
        t = stat.tStat();
        nDays = stat.nDays();
      }
      double weight = w.baseWeight() * multiplier;
      if (w.enabled()) {
        baseSum += w.baseWeight();
        weightedSum += weight;
      }
      updated.add(w.toBuilder().multiplier(round(multiplier)).weight(weight).icMean(mean).icSe(se).tStat(t).nDays(nDays).flagged(flagged)
          .note(note).build());
    }
    // 재정규화: 활성 시그널 가중치 합이 사전 합과 같게 (종합 점수 스케일 고정)
    double scale = weightedSum == 0 ? 1.0 : baseSum / weightedSum;
    List<SignalWeightRow> normalized = new ArrayList<>();
    for (SignalWeightRow w : updated) {
      normalized.add(w.toBuilder().weight(round(w.enabled() ? w.weight() * scale : 0.0)).build());
    }
    return normalized;
  }

  /**
   * IC 통계가 있는 학습 대상 시그널 중 가장 작은 n_eff (게이트 판정용). 통계가 하나도 없으면 0.
   * 통계가 없는 시그널(값이 전부 NULL 이거나 순위가 전부 동률)은 배수 1.0 을 유지하므로 게이트를 막지 않는다.
   */
  public static double minLearnableNEff(Map<String, IcStat> stats) {
    return SignalCode.learnable().stream()
        .filter(s -> stats.containsKey(s.getCode()))
        .mapToDouble(s -> stats.get(s.getCode()).nEff())
        .min().orElse(0);
  }

  static double round(double v) {
    return Math.round(v * 1e6) / 1e6;
  }
}

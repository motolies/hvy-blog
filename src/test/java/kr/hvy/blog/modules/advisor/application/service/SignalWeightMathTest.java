package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.SignalIcRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IC → 가중치 배수 산식을 수치로 고정한다 (축소·클립·재정규화·플래그·게이트).
 */
class SignalWeightMathTest {

  private final AdvisorProperties.Ic ic = new AdvisorProperties.Ic(); // window 120, ref 0.03, prior 24, min 24, clip 0.5~2.0

  @Test
  @DisplayName("창 통계: 평균·표준오차(√n_eff 보정)·t 를 계산한다")
  void aggregateComputesStats() {
    List<SignalIcRow> rows = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      rows.add(new SignalIcRow("MOM_20D", LocalDate.of(2026, 1, 1).plusDays(i), 5, i % 2 == 0 ? 0.05 : 0.01, 1000));
    }
    Map<String, SignalWeightMath.IcStat> stats = SignalWeightMath.aggregate(rows, 5);
    SignalWeightMath.IcStat s = stats.get("MOM_20D");
    assertThat(s.nDays()).isEqualTo(120);
    assertThat(s.nEff()).isEqualTo(24.0);
    assertThat(s.mean()).isCloseTo(0.03, within(1e-9));
    double std = Math.sqrt(120.0 / 119.0 * 0.02 * 0.02); // 표본 표준편차
    assertThat(s.se()).isCloseTo(std / Math.sqrt(24), within(1e-9));
    assertThat(s.tStat()).isCloseTo(0.03 / (std / Math.sqrt(24)), within(1e-6));
  }

  @Test
  @DisplayName("배수 = 1 + n_eff/(n_eff+prior)·(ĪC/ref − 1), clip [0.5, 2.0], 음의 IC 는 하한 + flagged, 밸류는 1.0 고정, 합은 재정규화")
  void applyShrinksClipsFlagsAndRenormalizes() {
    Map<String, SignalWeightMath.IcStat> stats = Map.of(
        "MOM_20D", new SignalWeightMath.IcStat("MOM_20D", 120, 24, 0.045, 0.01, 4.5),     // raw 1.5 → m̂ = 1 + 0.5·0.5 = 1.25
        "TV_SURGE", new SignalWeightMath.IcStat("TV_SURGE", 120, 24, 0.30, 0.01, 30),      // raw 10 → clip 2.0
        "VOL_20D", new SignalWeightMath.IcStat("VOL_20D", 120, 24, -0.02, 0.01, -2.0),     // 음수 → 하한 0.5, flagged
        "VALUE_RANK", new SignalWeightMath.IcStat("VALUE_RANK", 120, 24, 0.10, 0.01, 10)); // 학습 대상 아님 → 1.0
    List<SignalWeightRow> current = seed();

    List<SignalWeightRow> updated = SignalWeightMath.apply(stats, current, ic);
    Map<String, SignalWeightRow> byCode = new java.util.HashMap<>();
    updated.forEach(w -> byCode.put(w.signalCode(), w));

    assertThat(byCode.get("MOM_20D").multiplier()).isCloseTo(1.25, within(1e-6));
    assertThat(byCode.get("TV_SURGE").multiplier()).isEqualTo(2.0);
    assertThat(byCode.get("VOL_20D").multiplier()).isEqualTo(0.5);
    assertThat(byCode.get("VOL_20D").flagged()).isTrue();
    assertThat(byCode.get("VALUE_RANK").multiplier()).isEqualTo(1.0);
    assertThat(byCode.get("VALUE_RANK").icMean()).isNull();
    assertThat(byCode.get("MOM_60D").multiplier()).as("통계 없는 시그널은 1.0").isEqualTo(1.0);
    assertThat(byCode.get("GLOBAL_LINK").weight()).as("비활성은 0").isEqualTo(0.0);

    double sum = updated.stream().filter(SignalWeightRow::enabled).mapToDouble(SignalWeightRow::weight).sum();
    assertThat(sum).as("활성 가중치 합 = 사전 합 1.0 (소수 6자리 반올림 오차 허용)").isCloseTo(1.0, within(1e-5));
    assertThat(byCode.get("MOM_60D").note()).as("통계 없는 학습 시그널은 표시").isEqualTo("IC 없음 — 배수 1.0 유지");
    // 비율 보존: MOM_20D : MOM_60D = (0.12·1.25) : (0.10·1.0)
    assertThat(byCode.get("MOM_20D").weight() / byCode.get("MOM_60D").weight()).isCloseTo(0.15 / 0.10, within(1e-6));
  }

  @Test
  @DisplayName("게이트용 최소 n_eff 는 통계가 있는 학습 시그널 중 가장 작은 값이고(없는 시그널은 게이트를 막지 않음), 통계가 전혀 없으면 0")
  void minLearnableNEff() {
    Map<String, SignalWeightMath.IcStat> stats = new java.util.HashMap<>();
    for (SignalCode s : SignalCode.learnable()) {
      stats.put(s.getCode(), new SignalWeightMath.IcStat(s.getCode(), 100, 20, 0.02, 0.01, 2));
    }
    assertThat(SignalWeightMath.minLearnableNEff(stats)).isEqualTo(20.0);
    stats.put("TV_SURGE", new SignalWeightMath.IcStat("TV_SURGE", 50, 10, 0.02, 0.01, 2));
    assertThat(SignalWeightMath.minLearnableNEff(stats)).isEqualTo(10.0);
    stats.remove("RS_INDEX");
    assertThat(SignalWeightMath.minLearnableNEff(stats)).as("값이 전부 NULL 인 시그널이 세트 갱신을 막으면 안 된다").isEqualTo(10.0);
    assertThat(SignalWeightMath.minLearnableNEff(Map.of())).isEqualTo(0.0);
  }

  private static List<SignalWeightRow> seed() {
    List<SignalWeightRow> rows = new ArrayList<>();
    for (SignalCode s : SignalCode.values()) {
      rows.add(SignalWeightRow.builder().signalCode(s.getCode()).baseWeight(s.getBaseWeight()).multiplier(1.0).weight(s.getBaseWeight())
          .enabled(s.getBaseWeight() > 0).flagged(false).build());
    }
    return rows;
  }
}

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

  private final AdvisorProperties.Ic ic = new AdvisorProperties.Ic(); // window 120, ref 0.03, prior 24, min 24, clip 0~2.0

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
  @DisplayName("배수 = 1 + n_eff/(n_eff+prior)·(ĪC/ref − 1), clip [0, 2.0], 음의 IC 는 0 까지 + flagged, IC 로 검증된 시그널끼리만 재정규화하고 밸류·IC 없음은 사전 가중치 고정")
  void applyShrinksClipsFlagsAndRenormalizes() {
    Map<String, SignalWeightMath.IcStat> stats = Map.of(
        "MOM_20D", new SignalWeightMath.IcStat("MOM_20D", 120, 24, 0.045, 0.01, 4.5),     // raw 1.5 → m̂ = 1 + 0.5·0.5 = 1.25
        "TV_SURGE", new SignalWeightMath.IcStat("TV_SURGE", 120, 24, 0.30, 0.01, 30),      // raw 10 → clip 2.0
        "VOL_20D", new SignalWeightMath.IcStat("VOL_20D", 120, 24, -0.02, 0.01, -2.0),     // raw −2/3 → m̂ = 1/6, flagged
        "TREND_MA", new SignalWeightMath.IcStat("TREND_MA", 120, 24, -0.06, 0.01, -6.0),   // raw −2 → m̂ = −0.5 → 하한 0, flagged
        "VALUE_RANK", new SignalWeightMath.IcStat("VALUE_RANK", 120, 24, 0.10, 0.01, 10)); // 학습 대상 아님 → 1.0
    List<SignalWeightRow> current = seed();

    List<SignalWeightRow> updated = SignalWeightMath.apply(stats, current, ic);
    Map<String, SignalWeightRow> byCode = new java.util.HashMap<>();
    updated.forEach(w -> byCode.put(w.signalCode(), w));

    assertThat(byCode.get("MOM_20D").multiplier()).isCloseTo(1.25, within(1e-6));
    assertThat(byCode.get("TV_SURGE").multiplier()).isEqualTo(2.0);
    assertThat(byCode.get("VOL_20D").multiplier()).isCloseTo(1.0 / 6, within(1e-6));
    assertThat(byCode.get("VOL_20D").flagged()).isTrue();
    assertThat(byCode.get("TREND_MA").multiplier()).as("ĪC ≤ −ref 는 하한 0").isEqualTo(0.0);
    assertThat(byCode.get("TREND_MA").weight()).as("부호를 뒤집지 않고 0 — 점수에서 빠진다").isEqualTo(0.0);
    assertThat(byCode.get("TREND_MA").enabled()).isTrue();
    assertThat(byCode.get("TREND_MA").flagged()).isTrue();
    assertThat(byCode.get("VALUE_RANK").multiplier()).isEqualTo(1.0);
    assertThat(byCode.get("VALUE_RANK").icMean()).isNull();
    assertThat(byCode.get("VALUE_RANK").weight()).as("IC 로 검증되지 않은 밸류는 재정규화에서 빠져 사전 가중치 그대로").isEqualTo(0.05);
    assertThat(byCode.get("MOM_60D").multiplier()).as("통계 없는 시그널은 1.0").isEqualTo(1.0);
    assertThat(byCode.get("MOM_60D").weight()).as("통계 없는 학습 시그널도 사전 가중치 고정").isEqualTo(0.10);
    assertThat(byCode.get("GLOBAL_LINK").weight()).as("비활성은 0").isEqualTo(0.0);

    // 검증된 4개(MOM_20D·TV_SURGE·VOL_20D·TREND_MA)의 합 = 그 사전 합 0.37, 전체 활성 합 = 사전 합 1.02
    double validated = byCode.get("MOM_20D").weight() + byCode.get("TV_SURGE").weight() + byCode.get("VOL_20D").weight() + byCode.get("TREND_MA").weight();
    assertThat(validated).isCloseTo(0.12 + 0.10 + 0.03 + 0.12, within(1e-5));
    double sum = updated.stream().filter(SignalWeightRow::enabled).mapToDouble(SignalWeightRow::weight).sum();
    double baseSum = SignalCode.scorable().stream().mapToDouble(SignalCode::getBaseWeight).sum();
    assertThat(sum).as("활성 가중치 합 = 사전 합 Σbase(1.02, 소수 6자리 반올림 오차 허용)").isCloseTo(baseSum, within(1e-5));
    assertThat(byCode.get("SECTOR_MOM_60D").multiplier()).as("새 섹터 시그널도 통계 없으면 1.0").isEqualTo(1.0);
    assertThat(byCode.get("MOM_60D").note()).as("통계 없는 학습 시그널은 표시").isEqualTo("IC 없음 — 배수 1.0, 사전 가중치 고정");
    // 검증된 시그널끼리는 비율 보존: MOM_20D : TV_SURGE = (0.12·1.25) : (0.10·2.0). 소수 6자리 반올림이라 비율 오차 1e-4 까지 허용
    assertThat(byCode.get("MOM_20D").weight() / byCode.get("TV_SURGE").weight()).isCloseTo(0.15 / 0.20, within(1e-4));
    assertThat(SignalWeightMath.learnedWeightsAllZero(updated)).isFalse();
  }

  @Test
  @DisplayName("옛 세트에 남은 RS_INDEX 행(표현식 없음)은 통계가 있어도 비활성·가중치 0 이고 재정규화에 들어가지 않는다")
  void legacyRsIndexRowIsDisabled() {
    List<SignalWeightRow> current = new ArrayList<>(seed());
    current.removeIf(w -> w.signalCode().equals("RS_INDEX"));
    current.add(SignalWeightRow.builder().signalCode("RS_INDEX").baseWeight(0.08).multiplier(0.5).weight(0.065876).enabled(true).flagged(true).build());
    Map<String, SignalWeightMath.IcStat> stats = Map.of(
        "RS_INDEX", new SignalWeightMath.IcStat("RS_INDEX", 120, 24, -0.035, 0.036, -0.98),
        "MOM_20D", new SignalWeightMath.IcStat("MOM_20D", 120, 24, 0.045, 0.01, 4.5));

    List<SignalWeightRow> updated = SignalWeightMath.apply(stats, current, ic);
    SignalWeightRow rs = updated.stream().filter(w -> w.signalCode().equals("RS_INDEX")).findFirst().orElseThrow();
    assertThat(rs.enabled()).isFalse();
    assertThat(rs.weight()).isEqualTo(0.0);
    assertThat(rs.icMean()).as("남은 RS_INDEX IC 통계는 보지 않는다").isNull();
    assertThat(rs.flagged()).isFalse();
    assertThat(rs.note()).isEqualTo("점수·IC 제외(표현식 없음) — 비활성");
    SignalWeightRow mom = updated.stream().filter(w -> w.signalCode().equals("MOM_20D")).findFirst().orElseThrow();
    assertThat(mom.weight()).as("검증 시그널이 MOM_20D 하나면 재정규화로 사전 가중치에 돌아온다").isCloseTo(0.12, within(1e-6));
  }

  @Test
  @DisplayName("학습 시그널 가중치가 전부 0 이면 퇴화 세트로 본다")
  void learnedWeightsAllZero() {
    Map<String, SignalWeightMath.IcStat> stats = new java.util.HashMap<>();
    for (SignalCode s : SignalCode.learnable()) {
      stats.put(s.getCode(), new SignalWeightMath.IcStat(s.getCode(), 120, 24, -0.05, 0.01, -5));
    }
    List<SignalWeightRow> updated = SignalWeightMath.apply(stats, seed(), ic);
    assertThat(SignalWeightMath.learnedWeightsAllZero(updated)).isTrue();
    assertThat(updated.stream().filter(w -> w.signalCode().equals("VALUE_RANK")).findFirst().orElseThrow().weight()).isEqualTo(0.05);
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
    stats.remove("SECTOR_MOM_60D");
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

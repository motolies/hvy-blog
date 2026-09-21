package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 12:00 편차 정량·분류 규칙을 고정한다: 시가 기준 우선·전일 종가 폴백, 반나절 σ 스케일, z 임계 경계, MARKET_DRAG 경계(절반 이상 지수 설명), AVOID 부호 반전, vol20 결손 → FLAT.
 */
class PickDeviationTest {

  private static final double Z = 1.0;

  @Test
  @DisplayName("sinceOpen·gap 은 분모가 없거나 0 이하면 null, 있으면 비율 − 1")
  void ratios() {
    assertThat(PickDeviation.sinceOpen(71000.0, 70000.0)).isCloseTo(1.0 / 70, within(1e-9));
    assertThat(PickDeviation.sinceOpen(71000.0, null)).isNull();
    assertThat(PickDeviation.sinceOpen(null, 70000.0)).isNull();
    assertThat(PickDeviation.sinceOpen(71000.0, 0.0)).isNull();
    assertThat(PickDeviation.gap(70000.0, 70200.0)).isCloseTo(70000.0 / 70200 - 1, within(1e-9));
  }

  @Test
  @DisplayName("초과는 픽·지수 시가가 다 있으면 OPEN 기준, 아니면 전일 대비율(%) 차이를 소수로 바꾼 PREV_CLOSE 폴백, 둘 다 없으면 null")
  void excessBasis() {
    PickDeviation.Excess open = PickDeviation.excess(0.014, 0.004, 1.2, 0.3);
    assertThat(open.basis()).isEqualTo(PickDeviation.BASIS_OPEN);
    assertThat(open.value()).isCloseTo(0.010, within(1e-9));

    PickDeviation.Excess fallback = PickDeviation.excess(0.014, null, 1.2, 0.4);
    assertThat(fallback.basis()).isEqualTo(PickDeviation.BASIS_PREV_CLOSE);
    assertThat(fallback.value()).isCloseTo(0.008, within(1e-9));

    assertThat(PickDeviation.excess(null, null, null, 0.4)).isNull();
    assertThat(PickDeviation.excess(0.014, null, 1.2, null)).isNull();
  }

  @Test
  @DisplayName("z 는 OPEN 기준이면 vol20 × √(3/6.5), PREV_CLOSE 기준이면 vol20 로 나눈다. vol20 이 없거나 0 이면 null")
  void zScale() {
    PickDeviation.Excess open = new PickDeviation.Excess(0.01, PickDeviation.BASIS_OPEN);
    assertThat(PickDeviation.z(open, 0.02)).isCloseTo(0.01 / (0.02 * Math.sqrt(3.0 / 6.5)), within(1e-9));
    PickDeviation.Excess prev = new PickDeviation.Excess(0.01, PickDeviation.BASIS_PREV_CLOSE);
    assertThat(PickDeviation.z(prev, 0.02)).isCloseTo(0.5, within(1e-9));
    assertThat(PickDeviation.z(open, null)).isNull();
    assertThat(PickDeviation.z(open, 0.0)).isNull();
    assertThat(PickDeviation.z(null, 0.02)).isNull();
  }

  @Test
  @DisplayName("LONG 분류 경계: |z| < 임계 FLAT, 임계 ≤ z < 2×임계 ON_TRACK, z ≥ 2×임계 OVERSHOOT")
  void longUpside() {
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.005, 0.004, 0.99, 0.7, Z)).isEqualTo(PickNoteClass.FLAT);
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.005, -0.004, -0.99, 0.7, Z)).isEqualTo(PickNoteClass.FLAT);
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.01, 0.008, 1.0, 1.2, Z)).isEqualTo(PickNoteClass.ON_TRACK);
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.02, 0.015, 1.99, 2.5, Z)).isEqualTo(PickNoteClass.ON_TRACK);
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.03, 0.025, 2.0, 2.4, Z)).isEqualTo(PickNoteClass.OVERSHOOT);
    // 픽은 내렸지만 지수가 더 내려 초과가 +인 경우도 방향 일치
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.005, 0.012, 1.3, -0.5, Z)).isEqualTo(PickNoteClass.ON_TRACK);
  }

  @Test
  @DisplayName("LONG 하방: 하락분의 절반 이상이 지수로 설명되면 MARKET_DRAG(경계 포함), 아니면 IDIOSYNCRATIC. 오르고도 지수에 뒤처지면 IDIOSYNCRATIC")
  void longDownside() {
    // 픽 −3%, 지수 −2% → 초과 −1% (|excess| 1% ≤ |move|/2 1.5%) → 시장 탓
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.03, -0.01, -1.2, -3.6, Z)).isEqualTo(PickNoteClass.MARKET_DRAG);
    // 경계: 초과가 정확히 절반
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.03, -0.015, -1.2, -2.4, Z)).isEqualTo(PickNoteClass.MARKET_DRAG);
    // 픽 −3%, 지수 −0.5% → 초과 −2.5% → 종목 고유
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.03, -0.025, -2.0, -2.4, Z)).isEqualTo(PickNoteClass.IDIOSYNCRATIC);
    // 픽 +0.5% 인데 지수 +3% → 뒤처짐
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.005, -0.025, -1.5, 0.3, Z)).isEqualTo(PickNoteClass.IDIOSYNCRATIC);
  }

  @Test
  @DisplayName("초과는 미미해도 지수와 같이 −임계σ 이상 빠졌으면 MARKET_DRAG(회고 대상) — zMove 가 없거나 임계 안이면 FLAT, AVOID 는 시장 덕에 오른 경우")
  void marketDragWithoutExcess() {
    double sigma = 0.02 * PickDeviation.HALF_DAY_SCALE;
    // 픽 −3%, 지수 −3% → 초과 0·z 0, zMove = −0.03/σ ≈ −2.2 → 시장과 함께 빠졌다
    Double zMove = PickDeviation.zOf(-0.03, PickDeviation.BASIS_OPEN, 0.02);
    assertThat(zMove).isCloseTo(-0.03 / sigma, within(1e-9));
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.03, 0.0, 0.0, zMove, Z)).isEqualTo(PickNoteClass.MARKET_DRAG);
    // 경계: zMoveS 가 정확히 −임계
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.0136, 0.0, 0.0, -1.0, Z)).isEqualTo(PickNoteClass.MARKET_DRAG);
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.013, 0.0, 0.0, -0.99, Z)).isEqualTo(PickNoteClass.FLAT);
    // 지수와 같이 +3% 오른 LONG 은 초과 0 → FLAT (드래그는 하방만)
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.03, 0.0, 0.0, 2.2, Z)).isEqualTo(PickNoteClass.FLAT);
    // AVOID 가 지수와 같이 +3% 오르면 부호 반전으로 MARKET_DRAG
    assertThat(PickDeviation.classify(PickDirection.AVOID, 0.03, 0.0, 0.0, 2.2, Z)).isEqualTo(PickNoteClass.MARKET_DRAG);
    assertThat(PickDeviation.classify(PickDirection.AVOID, -0.03, 0.0, 0.0, -2.2, Z)).isEqualTo(PickNoteClass.FLAT);
    // zMove 없음(σ 결손) → 첫 규칙만 건너뛰고 FLAT
    assertThat(PickDeviation.classify(PickDirection.LONG, -0.03, 0.0, 0.0, null, Z)).isEqualTo(PickNoteClass.FLAT);
    assertThat(PickDeviation.zOf(-0.03, PickDeviation.BASIS_OPEN, null)).isNull();
    assertThat(PickDeviation.zOf(null, PickDeviation.BASIS_OPEN, 0.02)).isNull();
    assertThat(PickDeviation.zOf(-0.03, PickDeviation.BASIS_PREV_CLOSE, 0.02)).isCloseTo(-1.5, within(1e-9));
  }

  @Test
  @DisplayName("AVOID 는 부호 반전: 지수보다 못하면 ON_TRACK, 지수 덕에 오르면 MARKET_DRAG, 혼자 오르면 IDIOSYNCRATIC, 크게 못하면 OVERSHOOT")
  void avoidInverts() {
    assertThat(PickDeviation.classify(PickDirection.AVOID, -0.02, -0.015, -1.5, -2.0, Z)).isEqualTo(PickNoteClass.ON_TRACK);
    assertThat(PickDeviation.classify(PickDirection.AVOID, -0.04, -0.035, -2.5, -3.0, Z)).isEqualTo(PickNoteClass.OVERSHOOT);
    // 회피 종목이 +3% 올랐지만 지수가 +2% → 초과 +1% (≤ 1.5%) → 시장이 끌어올렸다
    assertThat(PickDeviation.classify(PickDirection.AVOID, 0.03, 0.01, 1.2, 3.6, Z)).isEqualTo(PickNoteClass.MARKET_DRAG);
    // 회피 종목이 혼자 +3% → 종목 고유
    assertThat(PickDeviation.classify(PickDirection.AVOID, 0.03, 0.025, 2.0, 2.4, Z)).isEqualTo(PickNoteClass.IDIOSYNCRATIC);
    assertThat(PickDeviation.classify(PickDirection.AVOID, 0.003, 0.002, 0.5, 0.7, Z)).isEqualTo(PickNoteClass.FLAT);
  }

  @Test
  @DisplayName("z 또는 move 가 없으면(vol20 결손·시가 결손) FLAT — 회고를 생략한다. 임계를 바꾸면 경계도 따라간다")
  void missingInputsAreFlat() {
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.03, 0.025, null, null, Z)).isEqualTo(PickNoteClass.FLAT);
    assertThat(PickDeviation.classify(PickDirection.LONG, null, 0.025, 2.0, 2.4, Z)).isEqualTo(PickNoteClass.FLAT);
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.03, null, 2.0, 2.4, Z)).isEqualTo(PickNoteClass.FLAT);
    // 시가 없음 → vol20 은 있어도 z 는 PREV_CLOSE 기준(하루 σ)이라 더 보수적으로 FLAT 이 된다
    PickDeviation.Excess fallback = PickDeviation.excess(null, null, 1.2, 0.4);
    Double z = PickDeviation.z(fallback, 0.01);
    Double zMove = PickDeviation.zOf(0.012, fallback.basis(), 0.01);
    assertThat(z).isCloseTo(0.8, within(1e-9));
    assertThat(zMove).isCloseTo(1.2, within(1e-9));
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.012, fallback.value(), z, zMove, Z)).isEqualTo(PickNoteClass.FLAT);
    assertThat(PickDeviation.classify(PickDirection.LONG, 0.012, fallback.value(), z, zMove, 0.5)).isEqualTo(PickNoteClass.ON_TRACK);
  }
}

package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 반올림 유틸을 고정한다: 보통 구간은 옛 식 {@code Math.round(v*1e6)/1e6} 과 비트 단위로 같고, 큰 금액은 Long 포화 없이 원값, 억원 환산은 정수.
 */
class AdvisorMathTest {

  @Test
  @DisplayName("보통 크기 값은 옛 Math.round 식과 정확히 같은 결과 — 음수 .5 동률·작은 값 포함")
  void smallValuesMatchLegacy() {
    double[] samples = {0.1234567, -0.0000005, 1.5e-7, 123.456789, -98765.4321, 0.975, 0.0, -0.3333335, 2.5e-5};
    for (double v : samples) {
      assertThat(AdvisorMath.round6(v)).as("round6(%s)", v).isEqualTo(Math.round(v * 1e6) / 1e6);
      assertThat(AdvisorMath.round4(v)).as("round4(%s)", v).isEqualTo(Math.round(v * 1e4) / 1e4);
    }
    assertThat(AdvisorMath.round6(1e-7)).as("6자리 아래는 0 — 수급 비율이 원 단위면 1e-3 이상이라 문제없다").isEqualTo(0.0);
    assertThat(AdvisorMath.round6(1e-6)).isEqualTo(1e-6);
  }

  @Test
  @DisplayName("회귀(2026-10-15): 원 단위 시총 1.3651061E15 는 포화값 9.223372036854775E12 가 아니라 원값")
  void largeValuesAreNotSaturated() {
    assertThat(AdvisorMath.round6(1_365_106_100_000_000d)).isEqualTo(1.3651061E15).isNotEqualTo(9.223372036854775E12);
    assertThat(AdvisorMath.round6(9.3e12)).isEqualTo(9.3e12);
    assertThat(AdvisorMath.round6(1e10)).isEqualTo(1e10);
    assertThat(AdvisorMath.round4(-5e14)).isEqualTo(-5e14);
    assertThat(AdvisorMath.round6(Double.NaN)).isNaN();
    assertThat(AdvisorMath.round6(Double.POSITIVE_INFINITY)).isInfinite();
  }

  @Test
  @DisplayName("프롬프트용 round(Double, digits): null·NaN·무한대는 null, 자릿수대로 반올림")
  void nullableRound() {
    assertThat(AdvisorMath.round(null, 4)).isNull();
    assertThat(AdvisorMath.round(Double.NaN, 4)).isNull();
    assertThat(AdvisorMath.round(Double.NEGATIVE_INFINITY, 4)).isNull();
    assertThat(AdvisorMath.round(0.123456, 4)).isEqualTo(0.1235);
    assertThat(AdvisorMath.round(1234.5678, 2)).isEqualTo(1234.57);
    assertThat(AdvisorMath.round(1_365_106_100_000_000d, 2)).isEqualTo(1.3651061E15);
  }

  @Test
  @DisplayName("원 → 억원 정수: 반올림, 1억 미만은 0, null 은 null")
  void eok() {
    assertThat(AdvisorMath.eok(286_000_000_000L)).isEqualTo(2860L);
    assertThat(AdvisorMath.eok(-41_000_000_000L)).isEqualTo(-410L);
    assertThat(AdvisorMath.eok(49_999_999L)).isZero();
    assertThat(AdvisorMath.eok(-4_578_915_000_000L)).isEqualTo(-45789L);
    assertThat(AdvisorMath.eok(null)).isNull();
  }
}

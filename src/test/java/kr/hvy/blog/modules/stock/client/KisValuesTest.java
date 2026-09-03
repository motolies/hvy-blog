package kr.hvy.blog.modules.stock.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KIS 응답 값 변환 규칙 — 빈 문자열/"-" 는 결측(null), 콤마·선행 부호 허용.
 */
class KisValuesTest {

  @Test
  @DisplayName("빈 문자열과 '-' 는 null 로 읽는다")
  void blankAndDashAreNull() {
    assertThat(KisValues.decimal("")).isNull();
    assertThat(KisValues.decimal("   ")).isNull();
    assertThat(KisValues.decimal("-")).isNull();
    assertThat(KisValues.decimal(null)).isNull();
    assertThat(KisValues.longValue("")).isNull();
    assertThat(KisValues.longValue("", 7L)).isEqualTo(7L);
    assertThat(KisValues.date("")).isNull();
    assertThat(KisValues.date("00000000")).isNull();
    assertThat(KisValues.date("bad")).isNull();
  }

  @Test
  @DisplayName("콤마·선행 부호·소수를 관대하게 파싱한다")
  void lenientNumberParsing() {
    assertThat(KisValues.decimal("1,234.50")).isEqualByComparingTo(new BigDecimal("1234.50"));
    assertThat(KisValues.decimal("+0.25")).isEqualByComparingTo(new BigDecimal("0.25"));
    assertThat(KisValues.decimal("-1234")).isEqualByComparingTo(new BigDecimal("-1234"));
    assertThat(KisValues.longValue("12345678901234")).isEqualTo(12345678901234L);
    assertThat(KisValues.longValue("99.9")).isEqualTo(99L);
    assertThat(KisValues.intValue("42")).isEqualTo(42);
  }

  @Test
  @DisplayName("yyyyMMdd 를 LocalDate 로 상호 변환한다")
  void dateRoundTrip() {
    LocalDate date = LocalDate.of(2026, 9, 3);
    assertThat(KisValues.date("20260903")).isEqualTo(date);
    assertThat(KisValues.format(date)).isEqualTo("20260903");
  }

  @Test
  @DisplayName("Y/N 플래그는 Y 만 true")
  void flag() {
    assertThat(KisValues.flag("Y")).isTrue();
    assertThat(KisValues.flag("y")).isTrue();
    assertThat(KisValues.flag("N")).isFalse();
    assertThat(KisValues.flag("")).isFalse();
    assertThat(KisValues.flag(null)).isFalse();
  }
}

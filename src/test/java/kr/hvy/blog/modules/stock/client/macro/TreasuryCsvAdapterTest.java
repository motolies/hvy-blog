package kr.hvy.blog.modules.stock.client.macro;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재무부 CSV 파싱: 큰따옴표 헤더("2 Yr", "10 Yr")를 이름으로 찾고, 최신순 입력·N/A 결측·연말 연도 경계를 처리한다.
 */
class TreasuryCsvAdapterTest {

  private static final String CSV = """
      Date,"1 Mo","1.5 Month","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr","3 Yr","5 Yr","7 Yr","10 Yr","20 Yr","30 Yr"
      09/18/2026,3.97,3.98,4.10,4.14,4.24,4.24,4.44,4.76,4.83,4.86,4.93,5.01,5.38,5.34
      09/17/2026,3.97,3.98,4.09,4.12,4.23,4.20,4.40,4.67,4.75,4.78,4.86,4.94,5.32,5.29
      09/16/2026,3.97,3.98,4.09,4.12,4.23,4.20,4.40,N/A,4.75,4.78,4.86,,5.32,5.29
      """;

  @Test
  @DisplayName("10년물: 만기 열을 헤더 이름으로 찾아 [from, to] 만 남긴다")
  void parseTenYear() {
    List<MacroObservation> rows = TreasuryCsvAdapter.parse(CSV, MacroSeries.UST10Y, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18), 1);
    assertThat(rows).extracting(MacroObservation::obsDate).containsExactly(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 17));
    assertThat(rows.getFirst().value()).isEqualByComparingTo(new BigDecimal("5.01"));
    assertThat(rows.getFirst().source()).isEqualTo(MacroSource.TREASURY);
    assertThat(rows.getFirst().availableFrom()).isEqualTo(LocalDate.of(2026, 9, 19));
  }

  @Test
  @DisplayName("2년물: N/A·빈 값은 행을 만들지 않는다")
  void missingIsDropped() {
    List<MacroObservation> two = TreasuryCsvAdapter.parse(CSV, MacroSeries.UST2Y, LocalDate.MIN, LocalDate.MAX, 1);
    assertThat(two).extracting(MacroObservation::obsDate).containsExactly(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 17));
    List<MacroObservation> ten = TreasuryCsvAdapter.parse(CSV, MacroSeries.UST10Y, LocalDate.MIN, LocalDate.MAX, 1);
    assertThat(ten).as("09/16 의 10 Yr 이 빈 값").hasSize(2);
  }

  @Test
  @DisplayName("URL 의 {year} 자리 표시자")
  void yearPlaceholder() {
    assertThat("https://x/{year}/all?field_tdr_date_value={year}".replace(TreasuryCsvAdapter.YEAR_PLACEHOLDER, "2026"))
        .isEqualTo("https://x/2026/all?field_tdr_date_value=2026");
  }
}

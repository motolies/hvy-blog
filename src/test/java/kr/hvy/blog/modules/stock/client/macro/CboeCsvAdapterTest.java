package kr.hvy.blog.modules.stock.client.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CBOE CSV 파싱: 헤더 이름으로 열을 찾고, 범위 밖·날짜 불량·값 결측 행은 버리며 available_from = 관측일 + 지연.
 */
class CboeCsvAdapterTest {

  private static final String CSV = """
      DATE,OPEN,HIGH,LOW,CLOSE
      09/16/2026,16.910000,18.940000,16.400000,17.710000
      09/17/2026,16.030000,16.290000,15.380000,15.440000
      bad-date,1,2,3,4
      09/18/2026,15.070000,15.630000,14.800000,
      09/19/2026,15.000000,15.100000,14.900000,N/A
      """;

  @Test
  @DisplayName("범위 안 행만, 값이 있는 행만, 관측일 다음 날부터 알 수 있었던 것으로")
  void parse() {
    List<MacroObservation> rows = CboeCsvAdapter.parse(CSV, MacroSeries.VIX, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30), 1);
    assertThat(rows).hasSize(1);
    MacroObservation row = rows.getFirst();
    assertThat(row.series()).isEqualTo(MacroSeries.VIX);
    assertThat(row.obsDate()).isEqualTo(LocalDate.of(2026, 9, 17));
    assertThat(row.value()).isEqualByComparingTo(new BigDecimal("15.440000"));
    assertThat(row.source()).isEqualTo(MacroSource.CBOE);
    assertThat(row.availableFrom()).isEqualTo(LocalDate.of(2026, 9, 18));
  }

  @Test
  @DisplayName("전체 범위면 값 있는 2행 (결측 '' 과 N/A 는 행을 만들지 않는다 — 전진 보간 금지)")
  void missingValuesAreDropped() {
    List<MacroObservation> rows = CboeCsvAdapter.parse(CSV, MacroSeries.VIX, LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), 1);
    assertThat(rows).extracting(MacroObservation::obsDate).containsExactly(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 17));
  }

  @Test
  @DisplayName("헤더에 DATE 나 시리즈 열이 없거나 본문이 비면 원천 형식 변경으로 보고 즉시 실패 (조용한 0행 금지)")
  void headerChangeFails() {
    assertThatThrownBy(() -> CboeCsvAdapter.parse("Date,Close\n09/16/2026,1", MacroSeries.UST10Y, LocalDate.MIN, LocalDate.MAX, 1))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("10 Yr");
    assertThatThrownBy(() -> CboeCsvAdapter.parse("", MacroSeries.VIX, LocalDate.MIN, LocalDate.MAX, 1)).isInstanceOf(IllegalStateException.class);
  }
}

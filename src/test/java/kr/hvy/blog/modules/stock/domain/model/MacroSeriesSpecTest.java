package kr.hvy.blog.modules.stock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * yml 한 줄 형식 파싱 — 시리즈 스펙("시리즈:원천:URL", URL 의 ':' 보존)과 테마("코드:표시명:쿼리", 코드 10자·중복 거부).
 */
class MacroSeriesSpecTest {

  @Test
  @DisplayName("시리즈 스펙: URL 안의 ':' 와 '{year}' 를 그대로 보존한다")
  void seriesSpec() {
    MacroSeriesSpec spec = MacroSeriesSpec.parse("ust10y:treasury:https://home.treasury.gov/x/{year}/all?a=b:c");
    assertThat(spec.series()).isEqualTo(MacroSeries.UST10Y);
    assertThat(spec.source()).isEqualTo(MacroSource.TREASURY);
    assertThat(spec.url()).isEqualTo("https://home.treasury.gov/x/{year}/all?a=b:c");
    assertThatThrownBy(() -> MacroSeriesSpec.parse("VIX:CBOE")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MacroSeriesSpec.parse("VIX:FRED:https://x")).isInstanceOf(IllegalArgumentException.class);
    assertThat(MacroSeriesSpec.parseAll(null)).isEmpty();
  }

  @Test
  @DisplayName("테마: 코드는 대문자·숫자·밑줄 10자 이내, 쿼리의 ':' 보존, 코드 중복 거부")
  void theme() {
    EventTheme theme = EventTheme.parse("kr_geo:한반도·북한:(\"North Korea\" OR \"Korean Peninsula\") sourcelang:english");
    assertThat(theme.code()).isEqualTo("KR_GEO");
    assertThat(theme.name()).isEqualTo("한반도·북한");
    assertThat(theme.query()).isEqualTo("(\"North Korea\" OR \"Korean Peninsula\") sourcelang:english");
    assertThatThrownBy(() -> EventTheme.parse("US_CN_TRADE:미중:tariff")).as("11자").isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventTheme.parseAll(List.of("A:a:x", "A:b:y"))).hasMessageContaining("중복");
  }
}

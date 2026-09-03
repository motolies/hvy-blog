package kr.hvy.blog.modules.stock.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OverseasSymbolTest {

  @Test
  @DisplayName("구분:거래소:심볼 형식을 파싱하고 지수·환율은 거래소 없이 허용한다")
  void parse() {
    assertThat(OverseasSymbol.parse("N::.DJI")).isEqualTo(new OverseasSymbol("N", null, ".DJI"));
    assertThat(OverseasSymbol.parse("X::FX@KRW")).isEqualTo(new OverseasSymbol("X", null, "FX@KRW"));
    OverseasSymbol nvda = OverseasSymbol.parse("eq:nas:NVDA");
    assertThat(nvda).isEqualTo(new OverseasSymbol("EQ", "NAS", "NVDA"));
    assertThat(nvda.isEquity()).isTrue();
    assertThat(nvda.key()).isEqualTo("NVDA");
    assertThat(OverseasSymbol.parseAll(List.of("N::SPX", "EQ:AMS:XLF"))).hasSize(2);
  }

  @Test
  @DisplayName("형식·구분·거래소 누락은 기동 시 예외로 드러난다")
  void rejectsInvalid() {
    assertThatThrownBy(() -> OverseasSymbol.parse("NVDA")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OverseasSymbol.parse("Z::SPX")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OverseasSymbol.parse("EQ::NVDA")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OverseasSymbol.parse(null)).isInstanceOf(IllegalArgumentException.class);
    // 기본 설정값은 모두 유효해야 한다
    assertThat(OverseasSymbol.parseAll(new KisProperties().getOverseas().getSymbols())).hasSizeGreaterThan(20);
  }
}

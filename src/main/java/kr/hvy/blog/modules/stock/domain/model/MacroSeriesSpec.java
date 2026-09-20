package kr.hvy.blog.modules.stock.domain.model;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;

/**
 * yml {@code macro.series} 한 줄 "시리즈:원천:URL" 을 푼 것. URL 에 ':' 가 있으므로 앞 두 구분자까지만 자른다.
 * 재무부 URL 은 {@code {year}} 자리를 연도로 치환한다.
 */
public record MacroSeriesSpec(MacroSeries series, MacroSource source, String url) {

  /**
   * "VIX:CBOE:https://…" 형식 1줄을 파싱한다. 형식·코드가 틀리면 기동 시점에 IllegalArgumentException 으로 드러난다.
   */
  public static MacroSeriesSpec parse(String spec) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("macro.series 항목이 비어 있습니다");
    }
    String[] parts = spec.trim().split(":", 3);
    if (parts.length != 3 || parts[2].isBlank()) {
      throw new IllegalArgumentException("macro.series 형식 오류 (시리즈:원천:URL): " + spec);
    }
    return new MacroSeriesSpec(MacroSeries.valueOf(parts[0].trim().toUpperCase()), MacroSource.valueOf(parts[1].trim().toUpperCase()),
        parts[2].trim());
  }

  /**
   * 목록 전부 파싱 (null·빈 목록은 빈 결과).
   */
  public static List<MacroSeriesSpec> parseAll(List<String> specs) {
    List<MacroSeriesSpec> result = new ArrayList<>();
    if (specs != null) {
      for (String spec : specs) {
        result.add(parse(spec));
      }
    }
    return result;
  }
}

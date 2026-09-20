package kr.hvy.blog.modules.stock.client.macro;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.MacroDataPort;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import org.springframework.stereotype.Component;

/**
 * 시리즈의 원천(CBOE·재무부)에 맞는 CSV 어댑터로 위임한다. 원천 어댑터가 없으면 설정 오류이므로 즉시 실패한다.
 */
@Component
public class MacroSourceRouter implements MacroDataPort {

  private final Map<MacroSource, MacroCsvSource> sources = new EnumMap<>(MacroSource.class);

  public MacroSourceRouter(List<MacroCsvSource> adapters) {
    for (MacroCsvSource adapter : adapters) {
      if (sources.putIfAbsent(adapter.source(), adapter) != null) {
        throw new IllegalStateException("거시 원천 어댑터 중복: " + adapter.source());
      }
    }
  }

  @Override
  public SourceFetch<MacroObservation> fetchSeries(MacroSeriesSpec spec, LocalDate from, LocalDate to) {
    MacroCsvSource source = sources.get(spec.source());
    if (source == null) {
      throw new IllegalStateException("거시 원천 어댑터 없음: " + spec.source() + " (" + spec.series() + ")");
    }
    return source.fetch(spec, from, to);
  }
}

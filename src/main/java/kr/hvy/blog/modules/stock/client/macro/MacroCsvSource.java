package kr.hvy.blog.modules.stock.client.macro;

import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;

/**
 * 원천별 CSV 어댑터 계약. {@link MacroSourceRouter} 가 {@link MacroSeriesSpec#source()} 로 골라 위임한다.
 */
public interface MacroCsvSource {

  MacroSource source();

  SourceFetch<MacroObservation> fetch(MacroSeriesSpec spec, LocalDate from, LocalDate to);
}

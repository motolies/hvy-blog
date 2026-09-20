package kr.hvy.blog.modules.stock.client;

import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;

/**
 * 거시 지표 외부 원천 경계 (KIS 와 별개 — 토큰·레이트리미터·tb_stock_kis_api_failure 경로를 타지 않는다).
 * 구현체는 원천별 CSV 어댑터를 라우팅하는 {@code MacroSourceRouter} 하나다.
 */
public interface MacroDataPort {

  /**
   * 시리즈 1종의 [from, to] 관측치. 파싱 불가 행은 버리고 결측은 행을 만들지 않는다(전진 보간 금지).
   */
  SourceFetch<MacroObservation> fetchSeries(MacroSeriesSpec spec, LocalDate from, LocalDate to);
}

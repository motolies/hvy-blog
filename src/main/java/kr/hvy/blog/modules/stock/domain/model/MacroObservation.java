package kr.hvy.blog.modules.stock.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;

/**
 * 거시 지표 관측치 1건 (tb_stock_macro_daily).
 *
 * @param obsDate       현지 관측일 (미국 영업일)
 * @param availableFrom 우리가 이 값을 알 수 있었던 최초 날짜(원천 게시 규칙으로 산출). 특징 SQL 은 {@code available_from <= 기준일 AND obs_date < 기준일} 둘 다 건다
 */
public record MacroObservation(MacroSeries series, LocalDate obsDate, BigDecimal value, MacroSource source, LocalDate availableFrom) {
}

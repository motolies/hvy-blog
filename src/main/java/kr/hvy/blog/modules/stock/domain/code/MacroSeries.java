package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 거시 위험 지표 시리즈 (tb_stock_macro_daily.series_code). code 는 상수명과 같다.
 * <p>
 * KIS 가 주지 않는 지표만 외부 공개 CSV 로 받는다(2026-09-20). 어느 시리즈를 어느 원천 URL 에서 받을지는 yml {@code macro.series} 가 정하고,
 * 여기서는 원천 CSV 안에서 값을 담은 열 이름만 안다.
 */
@Getter
@AllArgsConstructor
public enum MacroSeries implements EnumCode<String> {
  VIX("VIX", "CBOE 변동성 지수 종가", "CLOSE"),
  UST10Y("UST10Y", "미국 10년물 국채 수익률(%)", "10 Yr"),
  UST2Y("UST2Y", "미국 2년물 국채 수익률(%)", "2 Yr");

  private final String code;
  private final String desc;

  /** 원천 CSV 에서 이 시리즈 값을 담은 열 이름 (CBOE: CLOSE, 재무부: 만기 열) */
  private final String column;
}

package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 기업행사 출처. KSD 예탁원 API, CHART_HINT 일봉 output2 힌트, MANUAL 수기.
 */
@Getter
@AllArgsConstructor
public enum CorporateActionSource implements EnumCode<String> {
  KSD("KSD", "예탁원 API"),
  CHART_HINT("CHART_HINT", "일봉 힌트"),
  MANUAL("MANUAL", "수기 입력");

  private final String code;
  private final String desc;
}

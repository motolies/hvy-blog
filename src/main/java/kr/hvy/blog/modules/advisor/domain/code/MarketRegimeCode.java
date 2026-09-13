package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 시장 국면.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum MarketRegimeCode implements EnumCode<String> {
  RISK_ON("RISK_ON", "위험 선호"),
  NEUTRAL("NEUTRAL", "중립"),
  RISK_OFF("RISK_OFF", "위험 회피");

  private final String code;
  private final String desc;
}

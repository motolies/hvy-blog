package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 추세 무효화 조건(LLM 출력) — "이 추세가 깨졌다고 볼 첫 신호". 수치 레벨은 받지 않는다(LLM 이 먼 레벨을 골라 적중을 사는 유인 차단).
 * 기준선은 지수 종가 대비 MA20/MA60 이벤트뿐이라 정답이 결정론으로 정해진다. code 는 상수명과 같다.
 */
@Getter
@AllArgsConstructor
public enum InvalidationType implements EnumCode<String> {
  NONE("NONE", "없음"),
  BELOW_MA20("BELOW_MA20", "20일선 하향 이탈"),
  BELOW_MA60("BELOW_MA60", "60일선 하향 이탈"),
  ABOVE_MA20("ABOVE_MA20", "20일선 상향 돌파"),
  ABOVE_MA60("ABOVE_MA60", "60일선 상향 돌파");

  private final String code;
  private final String desc;

  /**
   * 하향 이탈(강세·보합용) 조건인지.
   */
  public boolean isBelow() {
    return this == BELOW_MA20 || this == BELOW_MA60;
  }

  /**
   * 상향 돌파(약세용) 조건인지.
   */
  public boolean isAbove() {
    return this == ABOVE_MA20 || this == ABOVE_MA60;
  }

  /**
   * 추세 방향과 어울리는 조건인지 — 강세·보합은 하향 이탈, 약세는 상향 돌파만 뜻이 있다. NONE 은 어디에나 허용.
   */
  public boolean consistentWith(MarketTrendCode trend) {
    if (this == NONE || trend == null) {
      return true;
    }
    return trend == MarketTrendCode.BEAR ? isAbove() : isBelow();
  }
}

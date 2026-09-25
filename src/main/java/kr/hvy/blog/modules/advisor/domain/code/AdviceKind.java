package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 판단 종류 (tb_advisor_advice.advice_kind). 같은 (base_date, variant) 에 종류별로 한 행씩 공존할 수 있으므로 조회·집계는 종류를 반드시 명시한다.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·REST 파라미터(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum AdviceKind implements EnumCode<String> {
  DAILY("DAILY", "19:30 일일 판단(T+5)"),
  MORNING("MORNING", "아침 재판정(전일 저녁과 같은 창)"),
  H20("H20", "20거래일 호라이즌"),
  H60("H60", "60거래일 호라이즌"),
  H180("H180", "180거래일 호라이즌"),
  /** 채팅 요청 등 수시 판단 — KPI·게이트·IC 에서 제외 */
  ADHOC("ADHOC", "수시 판단");

  private final String code;
  private final String desc;
}

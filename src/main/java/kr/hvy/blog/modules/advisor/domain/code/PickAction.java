package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 아침 재판정(MORNING)이 전일 저녁 픽에 내린 조치. 저장 컬럼(tb_advisor_pick.action)은 M4 에서 생기고, 그 전에는 코드 집합 차이로 계산한다({@code AdviceComparison}).
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·REST 파라미터(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum PickAction implements EnumCode<String> {
  KEEP("KEEP", "유지"),
  ADD("ADD", "추가"),
  DROP("DROP", "제외");

  private final String code;
  private final String desc;
}

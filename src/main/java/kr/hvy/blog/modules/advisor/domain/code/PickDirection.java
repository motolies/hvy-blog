package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 픽 방향. 숏은 개인 실무 제약으로 두지 않고 AVOID 로 접는다.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum PickDirection implements EnumCode<String> {
  LONG("LONG", "매수 관점"),
  AVOID("AVOID", "후보 중 회피");

  private final String code;
  private final String desc;
}

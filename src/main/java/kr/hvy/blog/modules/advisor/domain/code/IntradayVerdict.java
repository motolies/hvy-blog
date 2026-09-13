package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 장중 점검 판정.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum IntradayVerdict implements EnumCode<String> {
  ON_TRACK("ON_TRACK", "예측 방향 유지"),
  MIXED("MIXED", "혼조"),
  OFF_TRACK("OFF_TRACK", "예측 이탈");

  private final String code;
  private final String desc;
}

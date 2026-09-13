package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * advisor run 트리거 출처.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum AdvisorTriggerType implements EnumCode<String> {
  SCHEDULER("SCHEDULER", "스케줄러"),
  API("API", "관리자 API");

  private final String code;
  private final String desc;
}

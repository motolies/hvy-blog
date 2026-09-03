package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * run 트리거 출처.
 */
@Getter
@AllArgsConstructor
public enum TriggerType implements EnumCode<String> {
  SCHEDULER("SCHEDULER", "스케줄러"),
  API("API", "관리자 API");

  private final String code;
  private final String desc;
}

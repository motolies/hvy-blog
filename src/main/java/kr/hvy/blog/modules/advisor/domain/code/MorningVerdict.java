package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 아침 점검 판정. 예상 갭(β × 밤사이 미국 수익률)이 임계(sigma-multiple × σ_1d) 미만이면 HOLD, 임계 이상이고 어제 지수 방향 예측과 같은 부호면 REINFORCE,
 * 반대 부호(또는 NEUTRAL 예측에 큰 갭)면 CAUTION. code 는 상수명과 같다.
 */
@Getter
@AllArgsConstructor
public enum MorningVerdict implements EnumCode<String> {
  REINFORCE("REINFORCE", "강화"),
  HOLD("HOLD", "유지"),
  CAUTION("CAUTION", "주의");

  private final String code;
  private final String desc;

  /** 심각도 순서(주의 > 강화 > 유지) — 지수별 판정을 하나로 합칠 때 쓴다 */
  public int severity() {
    return switch (this) {
      case CAUTION -> 2;
      case REINFORCE -> 1;
      case HOLD -> 0;
    };
  }
}

package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 변동성 국면(M6 합성 국면의 두 번째 축). KOSPI 지수 σ20(직전 20거래일 ret_1d 표준편차)을 기준일 이전 최대 5년 분포의 백분위로 나눈다 —
 * 백분위 &lt; advisor.regime.vol-low-pct 면 LOW, ≥ vol-high-pct 면 HIGH, 그 사이 NORMAL. 분포 이력이 vol-min-history-days 미만이면 UNKNOWN(정책 가산 없음).
 * code 는 상수명과 같다(advisor enum 규약).
 */
@Getter
@AllArgsConstructor
public enum VolRegimeCode implements EnumCode<String> {
  LOW("LOW", "저변동"),
  NORMAL("NORMAL", "보통"),
  HIGH("HIGH", "고변동"),
  UNKNOWN("UNKNOWN", "판정 불가");

  private final String code;
  private final String desc;
}

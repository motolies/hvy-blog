package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 추세 지속 기간 전망(LLM 출력) — "기준일 추세가 다른 국면으로 바뀔 때까지의 거래일 수" 버킷.
 * <p>
 * 경계(5·20)는 결정 호라이즌(advisor.horizon-days)과 진단 호라이즌 20 에 맞춰 있어 새 스케줄 없이 h=20 채점 패스에서 실현 버킷이 확정된다.
 * code 는 상수명과 같다.
 */
@Getter
@AllArgsConstructor
public enum TrendHorizon implements EnumCode<String> {
  WITHIN_5D("WITHIN_5D", "5거래일 안에 전환"),
  ABOUT_20D("ABOUT_20D", "6~20거래일 유지"),
  BEYOND_20D("BEYOND_20D", "20거래일 넘게 유지");

  private final String code;
  private final String desc;

  /**
   * 실현 버킷: 첫 전환 오프셋(거래일, 없으면 null)을 버킷으로 바꾼다.
   *
   * @param flipOffset   기준일 추세와 처음 달라진 거래일 오프셋(1부터), 전환 없으면 null
   * @param shortDays    WITHIN 경계(결정 호라이즌, 5)
   * @param horizonDays  채점 창(20)
   */
  public static TrendHorizon realized(Integer flipOffset, int shortDays, int horizonDays) {
    if (flipOffset == null || flipOffset > horizonDays) {
      return BEYOND_20D;
    }
    return flipOffset <= shortDays ? WITHIN_5D : ABOUT_20D;
  }
}

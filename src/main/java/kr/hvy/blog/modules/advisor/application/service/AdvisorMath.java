package kr.hvy.blog.modules.advisor.application.service;

/**
 * 후보 스냅샷·프롬프트·채팅 도구가 공유하는 수치 유틸. 반올림은 long 포화 없이, 금액은 억원 정수로.
 * <p>
 * {@code Math.round(v * 1e6) / 1e6} 은 |v·1e6| 이 2^63 을 넘으면 Long.MAX_VALUE 로 조용히 포화해 원 단위 시총(1.3e15)이 전부
 * 9.223372036854775E12 가 되던 결함(2026-10-15 점검 P0-1)이 있었다. |v·scale| 이 2^53 이상이면 double 이 이미 정수라 반올림이 무의미하므로
 * 원값을 돌려준다 — 그 아래 구간에서는 기존 식과 비트 단위로 같은 결과다.
 */
public final class AdvisorMath {

  /** 이 값 이상의 double 은 전부 정수라 반올림이 의미 없다(그리고 2^63 부터는 Math.round 가 포화한다) */
  private static final double MAX_SCALED = 0x1p53;

  private AdvisorMath() {
  }

  /**
   * 소수 4자리 반올림(백분위·커버리지용). 포화 없음.
   */
  public static double round4(double v) {
    return round(v, 1e4);
  }

  /**
   * 소수 6자리 반올림(시그널 원값·feature 용). 포화 없음 — 시총 같은 큰 금액은 원값 그대로.
   */
  public static double round6(double v) {
    return round(v, 1e6);
  }

  /**
   * 자릿수 지정 반올림(프롬프트용). null·NaN·무한대는 null.
   */
  public static Double round(Double v, int digits) {
    if (v == null || v.isNaN() || v.isInfinite()) {
      return null;
    }
    return round(v, Math.pow(10, digits));
  }

  /**
   * 원 → 억원 정수(판단 프롬프트·채팅 도구 공통). null 은 null.
   */
  public static Long eok(Long won) {
    return won == null ? null : Math.round(won / 1e8);
  }

  /** 스케일 곱이 비유한이거나 2^53 이상이면 원값, 아니면 Math.round 로 반올림 */
  private static double round(double v, double scale) {
    double scaled = v * scale;
    if (!Double.isFinite(scaled) || Math.abs(scaled) >= MAX_SCALED) {
      return v;
    }
    return Math.round(scaled) / scale;
  }
}

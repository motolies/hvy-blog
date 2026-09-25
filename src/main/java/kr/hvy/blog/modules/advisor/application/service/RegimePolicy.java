package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;

/**
 * 사전 등록 정책 표(M6, regime-policy-v1): 합성 국면(추세 × 변동성) → 판단 가드 한도. 결정론·순수 함수이며 수치는 전부 advisor.regime.policy 에서 온다.
 * <pre>
 * | 추세     | LONG 상한                    | 확신 상한 | AVOID 최대 |
 * | BULL     | pick-max (기존)              | —         | 2 (기존)   |
 * | SIDEWAYS | pick-max (기존)              | 0.80      | 2 (기존)   |
 * | BEAR     | max(pick-min, pick-max − 2)  | 0.70      | 4          |
 * 변동성 HIGH: 확신 상한 −0.05 (상한이 없던 국면은 허용 최댓값 0.90 에서 뺀다)
 * </pre>
 * 표 수치를 바꾸면 advisor.regime.policy.version 을 올린다(새 버전) — 판단마다 버전이 regime_json 에 남아 사후에 버전별로 분리된다.
 * 확신 상한은 허용 이산값(AdviceSchemaFactory.CONVICTIONS) 중 상한 이하 최댓값으로 내린다 — 가드가 스냅한 값과 같은 격자여야 채점·보정 표가 깨지지 않는다.
 */
public final class RegimePolicy {

  /** 부동소수 비교 허용오차 (0.70 − 0.05 가 0.6499999… 가 되는 경우) */
  static final double EPS = 1e-9;

  private final AdvisorProperties properties;

  public RegimePolicy(AdvisorProperties properties) {
    this.properties = properties;
  }

  /**
   * 추세·변동성 국면의 한도. 추세가 없으면(지수 지표 없음) null — 호출자는 기존 가드만 적용한다.
   */
  public MarketRegime.Policy limits(MarketTrendCode trend, VolRegimeCode vol) {
    return limits(trend, vol, properties.getPickMin(), properties.getPickMax());
  }

  /**
   * 픽 범위를 지정한 한도(M7: H20 은 advisor.h20 의 pick-min·pick-max). LONG 상한 = max(pickMin, pickMax − 감산) 이라 같은 표가 종류별 범위에 비례해 적용된다.
   */
  public MarketRegime.Policy limits(MarketTrendCode trend, VolRegimeCode vol, int pickMin, int pickMax) {
    if (trend == null) {
      return null;
    }
    AdvisorProperties.RegimePolicyTable table = properties.getRegime().getPolicy();
    AdvisorProperties.RegimeRule rule = switch (trend) {
      case BULL -> table.getBull();
      case SIDEWAYS -> table.getSideways();
      case BEAR -> table.getBear();
    };
    int longMax = Math.max(pickMin, pickMax - rule.getLongMaxReduction());
    Double cap = rule.getConvictionCap();
    if (vol == VolRegimeCode.HIGH && table.getVolHighConvictionPenalty() > 0) {
      cap = (cap == null ? maxConviction() : cap) - table.getVolHighConvictionPenalty();
    }
    int avoidMax = rule.getAvoidMax() == null ? AdviceGuard.MAX_AVOID : rule.getAvoidMax();
    return new MarketRegime.Policy(table.getVersion(), longMax, cap == null ? null : snapDown(cap), avoidMax);
  }

  /**
   * 허용 이산값 중 cap 이하 최댓값. 전부 cap 보다 크면 최솟값(0.55).
   */
  static double snapDown(double cap) {
    double best = Double.NaN;
    double min = Double.MAX_VALUE;
    for (String allowed : AdviceSchemaFactory.CONVICTIONS) {
      double a = Double.parseDouble(allowed);
      min = Math.min(min, a);
      if (a <= cap + EPS && (Double.isNaN(best) || a > best)) {
        best = a;
      }
    }
    return Double.isNaN(best) ? min : best;
  }

  /** 허용 이산값 최댓값 (0.90) */
  static double maxConviction() {
    return AdviceSchemaFactory.CONVICTIONS.stream().mapToDouble(Double::parseDouble).max().orElse(0.90);
  }
}

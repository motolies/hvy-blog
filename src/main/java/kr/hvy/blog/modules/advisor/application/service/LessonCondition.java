package kr.hvy.blog.modules.advisor.application.service;

import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;

/**
 * 교훈 condition 술어 평가. 형식:
 * {"regime": RISK_ON|NEUTRAL|RISK_OFF|null, "trend": BULL|SIDEWAYS|BEAR|null, "signal": 시그널코드|null, "op": ">="|"<"|null, "pct": 0~1|null, "sector": 코드|null}.
 * 키가 하나 이상 null 이 아니어야 하며, null 인 키는 조건에서 제외된다. 기계 판정이 가능해야 "적용된 픽 vs 아닌 픽" 을 비교할 수 있다.
 * <p>
 * regime 은 LLM 출력이라 판단 시점엔 어제 값으로 평가하지만(AdviseJob), trend 는 규칙이 기준일에 확정한 오늘 값이라 지연이 없다(advice-v2).
 */
public final class LessonCondition {

  static final Set<String> KEYS = Set.of("regime", "trend", "signal", "op", "pct", "sector");

  private LessonCondition() {
  }

  /**
   * 후보에 조건이 참인지. regime 은 그날 판단 국면, trend 는 후보 소속 시장의 규칙 추세(없으면 해당 조건은 판정 불가 → false).
   */
  public static boolean matches(Map<String, Object> condition, CandidateRow candidate, MarketRegimeCode regime, MarketTrendCode trend) {
    if (condition == null || condition.isEmpty() || !isWellFormed(condition)) {
      return false;
    }
    Object r = condition.get("regime");
    if (r != null && (regime == null || !regime.name().equals(String.valueOf(r)))) {
      return false;
    }
    Object t = condition.get("trend");
    if (t != null && (trend == null || !trend.name().equals(String.valueOf(t)))) {
      return false;
    }
    Object sector = condition.get("sector");
    if (sector != null && !String.valueOf(sector).equals(candidate.sectorCode())) {
      return false;
    }
    Object signal = condition.get("signal");
    if (signal != null) {
      SignalValue value = candidate.signals() == null ? null : candidate.signals().get(String.valueOf(signal));
      if (value == null) {
        return false;
      }
      Object op = condition.get("op");
      Object pct = condition.get("pct");
      if (op == null || !(pct instanceof Number threshold)) {
        return false;
      }
      double th = threshold.doubleValue();
      return switch (String.valueOf(op)) {
        case ">=" -> value.pct() >= th;
        case "<" -> value.pct() < th;
        case ">" -> value.pct() > th;
        case "<=" -> value.pct() <= th;
        default -> false;
      };
    }
    return true;
  }

  /**
   * 저장 가능한 형식인지: 알려진 키만 있고 최소 하나는 null 이 아니며, signal 이 있으면 op·pct 도 있어야 한다.
   */
  public static boolean isWellFormed(Map<String, Object> condition) {
    if (condition == null || condition.isEmpty()) {
      return false;
    }
    for (String key : condition.keySet()) {
      if (!KEYS.contains(key)) {
        return false;
      }
    }
    boolean any = condition.get("regime") != null || condition.get("trend") != null || condition.get("signal") != null
        || condition.get("sector") != null;
    if (!any) {
      return false;
    }
    if (condition.get("signal") != null) {
      return condition.get("op") != null && condition.get("pct") instanceof Number;
    }
    return true;
  }
}

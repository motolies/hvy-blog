package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;

/**
 * 같은 기준일의 저녁(DAILY)·아침(MORNING) 픽 비교 — 순수 함수(채팅 compareAdvice, chat-v2).
 * <p>
 * 아침 픽에 조치(action)가 하나라도 기록돼 있으면(M4 이후) 그것을 그대로 쓰고, 없으면(M4 이전·컬럼 미도입) 코드 집합 차이로 계산한다:
 * 양쪽 = KEEP, 아침에만 = ADD, 저녁에만 = DROP. 계산한 사유는 "모델이 말한 이유" 가 아니므로 {@link Change#declared()} 로 구분한다 —
 * 채팅 모델이 계산값을 모델의 판단 근거처럼 인용하지 않게.
 */
public final class AdviceComparison {

  /** 조치 1건. declared=false 면 action·reason 은 집합 차이로 계산한 값 */
  public record Change(String ticker, PickAction action, String reason, PickRow evening, PickRow morning, boolean declared) {
  }

  private AdviceComparison() {
  }

  /**
   * 아침 픽 순서(순위)대로 KEEP·ADD(선언된 DROP 포함)를 먼저, 아침에 없는 저녁 픽을 저녁 순위대로 DROP 으로 뒤에 둔다.
   */
  public static List<Change> compare(List<PickRow> evening, List<PickRow> morning) {
    Map<String, PickRow> eveningByTicker = byTicker(evening);
    Map<String, PickRow> morningByTicker = byTicker(morning);
    boolean declared = morningByTicker.values().stream().anyMatch(p -> p.action() != null);
    List<Change> changes = new ArrayList<>();
    for (PickRow m : morningByTicker.values()) {
      PickRow e = eveningByTicker.get(m.ticker());
      if (declared && m.action() != null) {
        changes.add(new Change(m.ticker(), m.action(), m.actionReason(), e, m, true));
        continue;
      }
      PickAction action = e == null ? PickAction.ADD : PickAction.KEEP;
      changes.add(new Change(m.ticker(), action, derivedReason(action, e, m), e, m, false));
    }
    for (PickRow e : eveningByTicker.values()) {
      if (!morningByTicker.containsKey(e.ticker())) {
        changes.add(new Change(e.ticker(), PickAction.DROP, derivedReason(PickAction.DROP, e, null), e, null, false));
      }
    }
    return changes;
  }

  /**
   * 집합 차이로 계산한 조치의 설명. KEEP 은 방향·확신 변화를 적고, ADD 는 아침 근거 원문, DROP 은 사유 미기록임을 밝힌다.
   */
  static String derivedReason(PickAction action, PickRow evening, PickRow morning) {
    return switch (action) {
      case KEEP -> keepReason(evening, morning);
      case ADD -> morning == null || morning.thesis() == null ? "아침 재판정에서 새로 추가(사유 미기록)" : morning.thesis();
      case DROP -> "아침 재판정 픽에 없음(사유 미기록)";
    };
  }

  private static String keepReason(PickRow evening, PickRow morning) {
    List<String> diffs = new ArrayList<>();
    if (evening.direction() != morning.direction()) {
      diffs.add("방향 " + evening.direction() + "→" + morning.direction());
    }
    if (Math.abs(evening.conviction() - morning.conviction()) > 1e-9) {
      diffs.add(String.format(Locale.ROOT, "확신 %.2f→%.2f", evening.conviction(), morning.conviction()));
    }
    if (evening.pickRank() != morning.pickRank()) {
      diffs.add("순위 " + evening.pickRank() + "→" + morning.pickRank());
    }
    return diffs.isEmpty() ? "변화 없음" : String.join(", ", diffs);
  }

  private static Map<String, PickRow> byTicker(List<PickRow> picks) {
    Map<String, PickRow> m = new LinkedHashMap<>();
    if (picks != null) {
      picks.stream().sorted((a, b) -> Integer.compare(a.pickRank(), b.pickRank())).forEach(p -> m.putIfAbsent(p.ticker(), p));
    }
    return m;
  }
}

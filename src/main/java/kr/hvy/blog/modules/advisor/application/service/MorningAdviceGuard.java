package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.MorningAdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;

/**
 * 아침 재판정 출력 검증(2차 방어, 1차는 MorningAdviceSchemaFactory 의 enum). 규칙:
 * <ul>
 *   <li>DROP·KEEP 은 저녁 픽에만 — 저녁 픽 밖 결정은 무시(unknownDecision), 중복 결정은 첫 번째만(duplicateDecision)</li>
 *   <li>결정이 없는 저녁 픽은 KEEP 으로 보충(missingDecision) — 모델 누락을 "조용한 제외" 로 바꾸지 않는다</li>
 *   <li>ADD 는 저녁 후보 안이어야 하고(addOutsideCandidates) 저녁 픽에 이미 있으면 안 된다(addAlreadyPicked), 중복은 제거(duplicateAdd)</li>
 *   <li>최종 픽(KEEP + ADD)은 저녁과 같은 규칙: AVOID ≤ 2(초과는 확신 낮은 ADD 부터 제거), 상한 pick-max(초과는 확신 낮은 ADD 부터 제거),
 *       하한 pick-min(미달이면 DROP 을 저녁 순위대로 되돌려 KEEP — dropReverted)</li>
 *   <li>텍스트는 AdviceGuard 와 같은 정화·길이 절단, ADD 확신은 허용 이산값으로 스냅</li>
 * </ul>
 * 위반 항목은 제거하고 stats(카운터)·violations(티커·규칙 목록)에 남긴다. KEEP 은 저녁 픽의 방향·확신·근거를 그대로 잇는다 — 아침은 "뺄지·더할지" 만 정한다.
 */
public final class MorningAdviceGuard {

  static final int REASON_LIMIT = 300;
  static final String MISSING_DECISION_REASON = "모델 결정 누락 — 저녁 판단 유지";
  static final String REVERTED_REASON = "픽 하한 보충 — 제외 취소, 저녁 판단 유지";

  /** 제외된 저녁 픽 1개 (헤더 diff_json.drop 에만 남는다) */
  public record Drop(PickRow evening, String reason) {
  }

  /**
   * 검증 결과. picks 는 KEEP + ADD 를 확신 내림차순으로 순위 매긴 최종 픽(action·actionReason 채움), drops 는 제외한 저녁 픽.
   */
  public record Result(List<PickRow> picks, List<Drop> drops, String summary, Map<String, Object> stats, List<Map<String, String>> violations) {

    public List<PickRow> kept() {
      return picks.stream().filter(p -> p.action() == PickAction.KEEP).toList();
    }

    public List<PickRow> added() {
      return picks.stream().filter(p -> p.action() == PickAction.ADD).toList();
    }
  }

  private final AdvisorProperties properties;

  public MorningAdviceGuard(AdvisorProperties properties) {
    this.properties = properties;
  }

  /**
   * @param response     모델 출력 (null 이면 전부 KEEP)
   * @param eveningPicks 저녁 LIVE 픽 (저녁 순위)
   * @param candidates   저녁 후보 스냅샷 (ADD 허용 범위)
   */
  public Result validate(MorningAdviceResponse response, List<PickRow> eveningPicks, List<CandidateRow> candidates) {
    Map<String, Object> stats = new LinkedHashMap<>();
    List<Map<String, String>> violations = new ArrayList<>();
    Map<String, PickRow> evening = new LinkedHashMap<>();
    eveningPicks.stream().sorted(Comparator.comparingInt(PickRow::pickRank)).forEach(p -> evening.putIfAbsent(p.ticker(), p));
    Set<String> candidateTickers = new HashSet<>();
    candidates.forEach(c -> candidateTickers.add(c.ticker()));

    // ----- 저녁 픽 결정 (KEEP | DROP) -----
    Map<String, MorningAdviceResponse.Decision> decisions = new LinkedHashMap<>();
    if (response != null && response.decisions() != null) {
      for (MorningAdviceResponse.Decision d : response.decisions()) {
        String ticker = d == null || d.ticker() == null ? null : d.ticker().trim();
        if (ticker == null || !evening.containsKey(ticker)) {
          violate(stats, violations, ticker, "unknownDecision");
          continue;
        }
        if (decisions.putIfAbsent(ticker, d) != null) {
          violate(stats, violations, ticker, "duplicateDecision");
        }
      }
    }
    List<PickRow> kept = new ArrayList<>();
    List<Drop> drops = new ArrayList<>();
    for (PickRow e : evening.values()) {
      MorningAdviceResponse.Decision d = decisions.get(e.ticker());
      if (d == null) {
        increment(stats, "missingDecision");
        kept.add(keep(e, MISSING_DECISION_REASON));
        continue;
      }
      String reason = AdviceGuard.sanitize(d.reason(), REASON_LIMIT, stats);
      if (PickAction.DROP.getCode().equalsIgnoreCase(trim(d.action()))) {
        drops.add(new Drop(e, reason));
      } else {
        if (!PickAction.KEEP.getCode().equalsIgnoreCase(trim(d.action()))) {
          violate(stats, violations, e.ticker(), "badAction");
        }
        kept.add(keep(e, reason));
      }
    }

    // ----- 추가 (저녁 후보 안, 저녁 픽 밖) -----
    List<PickRow> added = new ArrayList<>();
    Set<String> seenAdds = new HashSet<>();
    if (response != null && response.additions() != null) {
      for (MorningAdviceResponse.Addition a : response.additions()) {
        String ticker = a == null || a.ticker() == null ? null : a.ticker().trim();
        if (ticker == null || !candidateTickers.contains(ticker)) {
          violate(stats, violations, ticker, "addOutsideCandidates");
          continue;
        }
        if (evening.containsKey(ticker)) {
          violate(stats, violations, ticker, "addAlreadyPicked");
          continue;
        }
        if (!seenAdds.add(ticker)) {
          violate(stats, violations, ticker, "duplicateAdd");
          continue;
        }
        PickDirection direction = direction(a.direction());
        if (direction == null) {
          violate(stats, violations, ticker, "badDirection");
          continue;
        }
        added.add(PickRow.builder().ticker(ticker).direction(direction)
            .conviction(AdviceGuard.conviction(a.conviction(), stats, "clampedConviction"))
            .thesis(AdviceGuard.sanitize(a.thesis(), AdviceGuard.THESIS_LIMIT, stats))
            .riskNote(AdviceGuard.sanitize(a.risk(), AdviceGuard.RISK_LIMIT, stats))
            .cited(List.of()).citedNews(List.of())
            .action(PickAction.ADD).actionReason(AdviceGuard.sanitize(a.reason(), REASON_LIMIT, stats)).build());
      }
    }

    // ----- 하한: DROP 을 저녁 순위대로 되돌린다 (ADD 로 채워지면 되돌리지 않는다) -----
    while (kept.size() + added.size() < properties.getPickMin() && !drops.isEmpty()) {
      Drop reverted = drops.removeFirst();
      violate(stats, violations, reverted.evening().ticker(), "dropReverted");
      kept.add(keep(reverted.evening(), REVERTED_REASON));
    }
    // ----- AVOID 상한·픽 상한: 저녁이 이미 통과한 KEEP 이 아니라 확신 낮은 ADD 부터 뺀다 -----
    trimAdds(added, kept, stats, violations);

    List<PickRow> all = new ArrayList<>(kept);
    all.addAll(added);
    // 확신 내림차순, 같으면 KEEP(저녁 순위) 먼저 — 정렬이 안정적이라 삽입 순서가 동률을 가른다
    all.sort((x, y) -> Double.compare(y.conviction(), x.conviction()));
    List<PickRow> ranked = new ArrayList<>();
    for (int i = 0; i < all.size(); i++) {
      ranked.add(all.get(i).toBuilder().pickRank(i + 1).build());
    }
    stats.put("keep", kept.size());
    stats.put("add", added.size());
    stats.put("drop", drops.size());
    String summary = AdviceGuard.sanitize(response == null ? null : response.summary(), AdviceGuard.SUMMARY_LIMIT, stats);
    return new Result(ranked, drops, summary, stats, violations);
  }

  /**
   * AVOID 는 KEEP·ADD 합쳐 최대 AdviceGuard.MAX_AVOID, 전체는 pick-max. 넘치면 확신 낮은 ADD 부터 제거한다(KEEP 은 저녁 가드를 이미 통과했다).
   */
  private void trimAdds(List<PickRow> added, List<PickRow> kept, Map<String, Object> stats, List<Map<String, String>> violations) {
    added.sort((x, y) -> Double.compare(y.conviction(), x.conviction()));
    long keptAvoids = kept.stream().filter(p -> p.direction() == PickDirection.AVOID).count();
    long avoids = keptAvoids + added.stream().filter(p -> p.direction() == PickDirection.AVOID).count();
    for (int i = added.size() - 1; i >= 0 && avoids > AdviceGuard.MAX_AVOID; i--) {
      if (added.get(i).direction() == PickDirection.AVOID) {
        violate(stats, violations, added.remove(i).ticker(), "truncatedAvoid");
        avoids--;
      }
    }
    while (!added.isEmpty() && kept.size() + added.size() > properties.getPickMax()) {
      violate(stats, violations, added.removeLast().ticker(), "truncated");
    }
  }

  private static PickRow keep(PickRow evening, String reason) {
    return evening.toBuilder().action(PickAction.KEEP).actionReason(reason).build();
  }

  private static PickDirection direction(String value) {
    try {
      return value == null ? null : PickDirection.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static void violate(Map<String, Object> stats, List<Map<String, String>> violations, String ticker, String rule) {
    increment(stats, rule);
    Map<String, String> v = new LinkedHashMap<>();
    v.put("ticker", ticker);
    v.put("rule", rule);
    violations.add(v);
  }

  private static void increment(Map<String, Object> stats, String key) {
    stats.merge(key, 1, (a, b) -> ((Integer) a) + ((Integer) b));
  }
}

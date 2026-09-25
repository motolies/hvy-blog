package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.advisor.client.llm.LongTermNarrativeResponse;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;

/**
 * 장기 서술 가드(M8): <b>규칙 결과가 정본</b>이다. LLM 은 선택·순위를 바꿀 수 없고, 응답의 종목 집합·순서가 규칙과 다르면 규칙 순서를 그대로 두고 서술만 티커로 매칭한다.
 * <ul>
 *   <li>규칙 밖 티커·중복 티커의 서술은 버린다(unknownTicker·duplicate)</li>
 *   <li>서술이 없는 규칙 픽은 thesis "서술 없음"(missing)</li>
 *   <li>LLM 이 실패했으면(response null) 전 픽 "서술 없음" + narrative=FAILED — fail-open, 발행은 규칙 픽으로 한다</li>
 *   <li>stats.ruleOverride = 응답의 (알려진 티커) 순서가 규칙 순서와 다르거나 개수가 달랐다 — LLM 이 선택을 바꾸려 한 관측치</li>
 * </ul>
 * 픽은 전부 LONG·확신 0.55 고정이다 — 규칙 선택에는 LLM 확신이 없고, 확신 보정 표(Brier)는 장기에 쓰지 않는다. 텍스트 정화·절단은 AdviceGuard 와 같다.
 */
public final class LongTermNarrativeGuard {

  public static final String NO_NARRATIVE = "서술 없음";
  static final double RULE_CONVICTION = 0.55;

  /** 검증 결과: 규칙 순서의 픽, 총평(없으면 null), 통계 */
  public record Result(List<PickRow> picks, String summary, Map<String, Object> stats) {
  }

  private LongTermNarrativeGuard() {
  }

  /**
   * @param ruleTickers 규칙이 확정한 픽 티커(순위 순)
   * @param response    LLM 응답 (실패면 null)
   * @param failure     LLM 실패 사유 (성공이면 null)
   */
  public static Result apply(List<String> ruleTickers, LongTermNarrativeResponse response, String failure) {
    Map<String, Object> stats = new LinkedHashMap<>();
    Set<String> rule = new HashSet<>(ruleTickers);
    Map<String, LongTermNarrativeResponse.Narrative> byTicker = new LinkedHashMap<>();
    List<String> answeredOrder = new ArrayList<>();
    if (response != null && response.picks() != null) {
      for (LongTermNarrativeResponse.Narrative n : response.picks()) {
        if (n == null || n.ticker() == null || !rule.contains(n.ticker().trim())) {
          increment(stats, "unknownTicker");
          continue;
        }
        String ticker = n.ticker().trim();
        if (byTicker.containsKey(ticker)) {
          increment(stats, "duplicate");
          continue;
        }
        byTicker.put(ticker, n);
        answeredOrder.add(ticker);
      }
    }
    List<PickRow> picks = new ArrayList<>();
    int missing = 0;
    for (int i = 0; i < ruleTickers.size(); i++) {
      String ticker = ruleTickers.get(i);
      LongTermNarrativeResponse.Narrative n = byTicker.get(ticker);
      String thesis = n == null ? null : AdviceGuard.sanitize(n.thesis(), AdviceGuard.THESIS_LIMIT, stats);
      if (thesis == null || thesis.isBlank()) {
        thesis = NO_NARRATIVE;
        missing++;
      }
      String risk = n == null ? null : AdviceGuard.sanitize(n.risk(), AdviceGuard.RISK_LIMIT, stats);
      picks.add(PickRow.builder().ticker(ticker).pickRank(i + 1).direction(PickDirection.LONG).conviction(RULE_CONVICTION)
          .thesis(thesis).riskNote(risk == null || risk.isBlank() ? null : risk).cited(List.of()).citedNews(List.of()).build());
    }
    boolean failed = response == null;
    stats.put("narrative", failed ? "FAILED" : missing == 0 ? "OK" : missing == ruleTickers.size() ? "EMPTY" : "PARTIAL");
    if (failed && failure != null) {
      stats.put("failure", AdviceGuard.sanitize(failure, 300, new LinkedHashMap<>()));
    }
    stats.put("missing", missing);
    stats.put("ruleOverride", !failed && !answeredOrder.equals(ruleTickers));
    String summary = response == null ? null : AdviceGuard.sanitize(response.summary(), AdviceGuard.SUMMARY_LIMIT, stats);
    return new Result(picks, summary == null || summary.isBlank() ? null : summary, stats);
  }

  private static void increment(Map<String, Object> stats, String key) {
    stats.merge(key, 1, (a, b) -> ((Integer) a) + ((Integer) b));
  }
}

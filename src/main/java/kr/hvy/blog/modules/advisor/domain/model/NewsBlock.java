package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프롬프트 news 블록(advice-v4): 판단 시각 이전 창의 헤드라인. id(N1…)는 그날 부여한 임시 키이며 스키마 citedNews enum 과 가드 대조에 쓴다.
 *
 * @param asOf     판단 시각(이 시각 이후 기사는 없다 — 룩어헤드 방어)
 * @param market   종목 태그 없는 시장 헤드라인
 * @param byTicker 후보 종목코드 → 그 종목이 태깅된 헤드라인 (같은 기사가 여러 종목에 붙을 수 있다)
 */
public record NewsBlock(Instant asOf, int windowHours, List<Headline> market, Map<String, List<Headline>> byTicker) {

  /** 헤드라인 1건 (time 은 "MM-dd HH:mm" KST) */
  public record Headline(String id, String time, String title, List<String> tickers) {
  }

  /**
   * 블록에 실린 모든 id (시장 → 종목 순, 중복 제거).
   */
  public List<String> ids() {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    market.forEach(h -> ids.add(h.id()));
    byTicker.values().forEach(list -> list.forEach(h -> ids.add(h.id())));
    return new ArrayList<>(ids);
  }

  /**
   * id → 그 기사가 태깅된 종목 집합 (시장 헤드라인은 빈 집합).
   */
  public Map<String, Set<String>> tickersById() {
    Map<String, Set<String>> map = new LinkedHashMap<>();
    market.forEach(h -> map.put(h.id(), Set.of()));
    byTicker.forEach((ticker, list) -> list.forEach(h -> map.computeIfAbsent(h.id(), k -> new LinkedHashSet<>()).add(ticker)));
    return map;
  }

  public int size() {
    return ids().size();
  }

  /**
   * 상한을 줄인 사본 (자 상한 초과 시 후보별 → 시장 순으로 줄이는 데 쓴다). 둘 다 0 이면 null.
   */
  public NewsBlock trimmed(int perTickerLimit, int marketLimit) {
    if (perTickerLimit <= 0 && marketLimit <= 0) {
      return null;
    }
    Map<String, List<Headline>> tickers = new LinkedHashMap<>();
    if (perTickerLimit > 0) {
      byTicker.forEach((t, list) -> {
        if (!list.isEmpty()) {
          tickers.put(t, list.subList(0, Math.min(perTickerLimit, list.size())));
        }
      });
    }
    List<Headline> mkt = market.subList(0, Math.min(Math.max(marketLimit, 0), market.size()));
    return new NewsBlock(asOf, windowHours, mkt, tickers);
  }
}

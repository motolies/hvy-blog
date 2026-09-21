package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CitedFeature;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.NewsBlock;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import org.apache.commons.lang3.StringUtils;

/**
 * 판단 출력 검증(환각·범위·개수·인젝션). 스키마 enum 이 1차 방어, 여기가 2차 방어다.
 * <ul>
 *   <li>후보 밖 티커·중복·모르는 direction 은 제거</li>
 *   <li>확신값은 허용 목록 밖이면 가장 가까운 허용값으로, 국면 확신도 동일</li>
 *   <li>citedFeatures 의 값이 입력 특징과 허용오차(상대 2% 또는 절대 1e-4) 밖이면 픽 제거(근거 위조)</li>
 *   <li>픽 > max 는 확신 내림차순 상위만, AVOID 는 최대 2개, 픽 < min 이면 tooFew</li>
 *   <li>텍스트에서 <!channel>·<!here>·제어문자 제거, 길이 절단</li>
 *   <li>advice-v6: secCons=0 후보·overheated 섹터의 LONG 픽은 확신을 advisor.advise.non-consistent-conviction-cap 으로 클램프(제거 아님, stats
 *       capNonConsistent/capOverheated). 주도 섹터 콜에는 입력 sectors 의 consistent 를 채운다</li>
 * </ul>
 * 제거율(제거 픽 / 원본 픽)이 30% 를 넘으면 모델·프롬프트가 어긋난 신호로 보고 run 을 PARTIAL 로 둔다.
 */
public final class AdviceGuard {

  /**
   * 가드가 보는 섹터 맥락(advice-v6). names 는 주도 섹터 검증·표기용 코드 → 이름(top·bottom 표 ∪ 후보 섹터), consistent 는 세 구간 모두 시장을 이긴 섹터,
   * overheated 는 과열 섹터. consistent/overheated 는 시장 특징(top·bottom)에서, 후보에만 있는 섹터의 consistent 는 후보의 secCons 로 보충한다.
   */
  public record SectorContext(Map<String, String> names, Set<String> consistent, Set<String> overheated) {

    /**
     * 시장 특징과 후보로 맥락을 만든다 (AdviseJob 의 LIVE·섀도가 같은 맥락을 쓴다).
     * <p>
     * 범위 한정: overheated 는 프롬프트 sectors 표에 실린 top 8·bottom 3 에서만 온다 — LLM 이 본 것과 같은 정보로만 클램프해야 "규칙 11 을 안 지켰다" 는
     * 관측(capOverheated)이 성립하고, 표 밖 섹터의 5일 수익률은 프롬프트에도 없다. 따라서 중간 순위(9~n−4위) 과열 섹터의 후보는 클램프되지 않는다(의도).
     * consistent 는 후보 행의 secCons(1) 로 보충할 수 있어 표 밖 섹터도 채워진다 — 후보 행에 실린 값이라 역시 LLM 이 본 정보다.
     */
    public static SectorContext of(MarketFeatures market, List<CandidateRow> candidates) {
      Map<String, String> names = new LinkedHashMap<>();
      Set<String> consistent = new HashSet<>();
      Set<String> overheated = new HashSet<>();
      List<MarketFeatures.SectorFeature> features = new ArrayList<>();
      if (market.topSectors() != null) {
        features.addAll(market.topSectors());
      }
      if (market.bottomSectors() != null) {
        features.addAll(market.bottomSectors());
      }
      for (MarketFeatures.SectorFeature s : features) {
        names.put(s.code(), s.name());
        if (Boolean.TRUE.equals(s.consistent())) {
          consistent.add(s.code());
        }
        if (Boolean.TRUE.equals(s.overheated())) {
          overheated.add(s.code());
        }
      }
      for (CandidateRow c : candidates) {
        if (c.sectorCode() == null) {
          continue;
        }
        names.putIfAbsent(c.sectorCode(), c.sectorName());
        if (Integer.valueOf(1).equals(AdvicePromptBuilder.secCons(c.features()))) {
          consistent.add(c.sectorCode());
        }
      }
      return new SectorContext(names, consistent, overheated);
    }

    /**
     * 이름만 있는 맥락 (consistent·overheated 없음 — 테스트·구버전 호출용).
     */
    public static SectorContext ofNames(Map<String, String> names) {
      return new SectorContext(names, Set.of(), Set.of());
    }
  }

  public static final double REMOVAL_ALERT_RATIO = 0.30;
  static final int MAX_AVOID = 2;
  static final int THESIS_LIMIT = 400;
  static final int RISK_LIMIT = 300;
  static final int RATIONALE_LIMIT = 900;
  static final int SUMMARY_LIMIT = 900;
  static final double CITED_REL_TOLERANCE = 0.02;
  static final double CITED_ABS_TOLERANCE = 1e-4;

  /** 검증 결과. outlooks 는 지수별 추세 지속 전망(0001·1001 순, 폴백 포함 항상 2개) */
  public record Result(MarketRegimeCode regime, DirectionCall kospiDir, DirectionCall kosdaqDir, double pUp, String rationale,
                       List<SectorCall> sectors, List<PickRow> picks, String summary, Map<String, Object> stats, int originalPicks, int removed,
                       List<TrendOutlook> outlooks) {

    public boolean tooFew(int min) {
      return picks.size() < min;
    }

    public double removalRatio() {
      return originalPicks == 0 ? 0 : (double) removed / originalPicks;
    }
  }

  private final AdvisorProperties properties;

  public AdviceGuard(AdvisorProperties properties) {
    this.properties = properties;
  }

  /**
   * @param sectors 섹터 맥락(이름·consistent·overheated)
   * @param trends  지수 코드 → 규칙 추세 (무효화 조건의 방향 일관성 검사용, 없으면 빈 맵)
   */
  public Result validate(AdviceResponse response, List<CandidateRow> candidates, SectorContext sectors, Map<String, MarketTrendCode> trends) {
    return validate(response, candidates, sectors, trends, null);
  }

  /**
   * @param news 프롬프트에 실린 뉴스 블록(없으면 null). citedNews 는 실린 id 만 남기고(unknownNews), 종목 픽이 다른 종목에만 태깅된 기사를 인용하면
   *             제거(newsMismatch) — 숫자 위조와 달리 픽은 버리지 않는다(시장 헤드라인을 종목 근거로 드는 건 정당하다).
   */
  public Result validate(AdviceResponse response, List<CandidateRow> candidates, SectorContext sectors, Map<String, MarketTrendCode> trends,
      NewsBlock news) {
    Map<String, Object> stats = new LinkedHashMap<>();
    Map<String, Set<String>> newsTickers = news == null ? Map.of() : news.tickersById();
    Map<String, String> sectorNames = sectors == null ? Map.of() : sectors.names();
    Set<String> consistentSectors = sectors == null ? Set.of() : sectors.consistent();
    Set<String> overheatedSectors = sectors == null ? Set.of() : sectors.overheated();
    Map<String, CandidateRow> byTicker = new LinkedHashMap<>();
    candidates.forEach(c -> byTicker.put(c.ticker(), c));

    // ----- 국면 -----
    AdviceResponse.Regime r = response.regime();
    MarketRegimeCode regime = enumOr(MarketRegimeCode.class, r == null ? null : r.code(), MarketRegimeCode.NEUTRAL, stats, "badRegime");
    DirectionCall kospi = enumOr(DirectionCall.class, r == null ? null : r.kospiDir(), DirectionCall.NEUTRAL, stats, "badKospiDir");
    DirectionCall kosdaq = enumOr(DirectionCall.class, r == null ? null : r.kosdaqDir(), DirectionCall.NEUTRAL, stats, "badKosdaqDir");
    double pUp = conviction(r == null ? null : r.pUp(), stats, "clampedPUp");
    String rationale = sanitize(r == null ? null : r.rationale(), RATIONALE_LIMIT, stats);

    // ----- 추세 지속 전망 (오류는 폴백·기록만, 픽 제거율에 넣지 않는다) -----
    AdviceResponse.TrendOutlookView tv = response.trendOutlook();
    List<TrendOutlook> outlooks = List.of(
        outlook("0001", tv == null ? null : tv.kospi(), trends == null ? null : trends.get("0001"), stats),
        outlook("1001", tv == null ? null : tv.kosdaq(), trends == null ? null : trends.get("1001"), stats));

    // ----- 섹터 (consistent 는 LLM 출력이 아니라 입력 맥락에서 채운다) -----
    List<SectorCall> sectorCalls = new ArrayList<>();
    Set<String> seenSectors = new HashSet<>();
    if (response.sectors() != null) {
      for (AdviceResponse.SectorView s : response.sectors()) {
        if (s == null || s.code() == null || !sectorNames.containsKey(s.code())) {
          increment(stats, "unknownSector");
          continue;
        }
        if (!seenSectors.add(s.code())) {
          increment(stats, "duplicateSector");
          continue;
        }
        sectorCalls.add(new SectorCall(s.code(), sectorNames.get(s.code()), sanitize(s.reason(), RISK_LIMIT, stats), consistentSectors.contains(s.code())));
        if (sectorCalls.size() == 4) {
          break;
        }
      }
    }

    // ----- 픽 -----
    List<PickRow> picks = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int original = response.picks() == null ? 0 : response.picks().size();
    int removed = 0;
    if (response.picks() != null) {
      for (AdviceResponse.Pick p : response.picks()) {
        if (p == null || p.ticker() == null || !byTicker.containsKey(p.ticker())) {
          increment(stats, "unknownTicker");
          removed++;
          continue;
        }
        if (!seen.add(p.ticker())) {
          increment(stats, "duplicate");
          removed++;
          continue;
        }
        PickDirection direction = enumOr(PickDirection.class, p.direction(), null, stats, "badDirection");
        if (direction == null) {
          removed++;
          continue;
        }
        CandidateRow candidate = byTicker.get(p.ticker());
        List<CitedFeature> cited = checkCited(p.citedFeatures(), candidate, stats);
        if (cited == null) {
          removed++;
          continue;
        }
        double conviction = conviction(p.conviction(), stats, "clampedConviction");
        if (direction == PickDirection.LONG) {
          conviction = capBySector(conviction, candidate, overheatedSectors, stats);
        }
        picks.add(PickRow.builder()
            .ticker(p.ticker())
            .direction(direction)
            .conviction(conviction)
            .thesis(sanitize(p.thesis(), THESIS_LIMIT, stats))
            .riskNote(sanitize(p.risk(), RISK_LIMIT, stats))
            .cited(cited)
            .citedNews(checkCitedNews(p.citedNews(), p.ticker(), newsTickers, stats))
            .build());
      }
    }
    // AVOID 상한
    List<PickRow> avoids = picks.stream().filter(p -> p.direction() == PickDirection.AVOID).toList();
    if (avoids.size() > MAX_AVOID) {
      List<PickRow> drop = avoids.stream().sorted((a, b) -> Double.compare(a.conviction(), b.conviction())).limit(avoids.size() - MAX_AVOID).toList();
      picks.removeAll(drop);
      removed += drop.size();
      stats.put("truncatedAvoid", drop.size());
    }
    // 확신 내림차순, 상한
    picks.sort((a, b) -> Double.compare(b.conviction(), a.conviction()));
    if (picks.size() > properties.getPickMax()) {
      stats.put("truncated", picks.size() - properties.getPickMax());
      picks = new ArrayList<>(picks.subList(0, properties.getPickMax()));
    }
    List<PickRow> ranked = new ArrayList<>();
    for (int i = 0; i < picks.size(); i++) {
      ranked.add(picks.get(i).toBuilder().pickRank(i + 1).build());
    }
    if (ranked.size() < properties.getPickMin()) {
      stats.put("tooFewPicks", ranked.size());
    }
    stats.put("originalPicks", original);
    stats.put("removed", removed);
    return new Result(regime, kospi, kosdaq, pUp, rationale, sectorCalls, ranked, sanitize(response.summary(), SUMMARY_LIMIT, stats), stats,
        original, removed, outlooks);
  }

  /**
   * advice-v6 섹터 규칙의 기계 클램프: 후보의 secCons 가 0(소속 업종 지수가 1주·1개월·3개월 중 하나라도 시장에 미달) 이거나 소속 섹터가 overheated 면
   * LONG 확신을 advisor.advise.non-consistent-conviction-cap 으로 내린다 — 제거가 아니라 클램프라 픽 제거율에 넣지 않는다. 실제로 내려간 경우만
   * capNonConsistent·capOverheated 를 세어 프롬프트 규칙 11 의 준수율을 관찰한다. secCons null(업종 지수 없음·창 부족)은 클램프하지 않는다.
   * AVOID 는 대상이 아니다 — 지속 미충족·과열은 매수 위험이지 회피의 근거를 약하게 하는 요인이 아니다.
   */
  private double capBySector(double conviction, CandidateRow candidate, Set<String> overheatedSectors, Map<String, Object> stats) {
    double cap = properties.getAdvise().getNonConsistentConvictionCap();
    if (candidate == null || conviction <= cap) {
      return conviction;
    }
    boolean nonConsistent = Integer.valueOf(0).equals(AdvicePromptBuilder.secCons(candidate.features()));
    boolean overheated = candidate.sectorCode() != null && overheatedSectors.contains(candidate.sectorCode());
    if (!nonConsistent && !overheated) {
      return conviction;
    }
    if (nonConsistent) {
      increment(stats, "capNonConsistent");
    }
    if (overheated) {
      increment(stats, "capOverheated");
    }
    return cap;
  }

  /**
   * 지수 1개의 추세 전망 검증: enum 밖은 ABOUT_20D / NONE 폴백, 추세 방향과 모순되는 무효화(강세인데 상향 돌파 등)는 NONE 으로 강등. 블록 자체가 없으면
   * (구버전 응답) missingOutlook 만 세고 기본값을 돌려준다.
   */
  static TrendOutlook outlook(String indexCode, AdviceResponse.Outlook o, MarketTrendCode trend, Map<String, Object> stats) {
    if (o == null) {
      increment(stats, "missingOutlook");
      return new TrendOutlook(indexCode, TrendHorizon.ABOUT_20D, 0.55, InvalidationType.NONE);
    }
    TrendHorizon persist = enumOr(TrendHorizon.class, o.persist(), TrendHorizon.ABOUT_20D, stats, "badPersist");
    double confidence = conviction(o.confidence(), stats, "clampedOutlookConfidence");
    InvalidationType invalidation = enumOr(InvalidationType.class, o.invalidation(), InvalidationType.NONE, stats, "badInvalidation");
    if (!invalidation.consistentWith(trend)) {
      increment(stats, "inconsistentInvalidation");
      invalidation = InvalidationType.NONE;
    }
    return new TrendOutlook(indexCode, persist, confidence, invalidation);
  }

  /**
   * 인용 특징을 후보의 특징·시그널 원값과 대조한다. 이름이 없거나 값이 허용오차 밖이면 null(픽 폐기). 인용이 없으면 빈 목록(허용).
   */
  private static List<CitedFeature> checkCited(List<AdviceResponse.Cited> cited, CandidateRow candidate, Map<String, Object> stats) {
    if (cited == null || cited.isEmpty()) {
      return List.of();
    }
    Map<String, Double> known = new LinkedHashMap<>();
    candidate.features().forEach((k, v) -> {
      if (v instanceof Number n) {
        known.put(k, n.doubleValue());
      }
    });
    known.put("score", candidate.quantScore());
    candidate.signals().forEach((k, v) -> {
      if (v.raw() != null) {
        known.put(k, v.raw());
      }
    });
    // 프롬프트 표 열 이름 ↔ 특징 키 별칭
    known.putIfAbsent("distHigh", known.get("distHigh52w"));
    known.putIfAbsent("frgnFlow", known.get("FOREIGN_FLOW"));
    known.putIfAbsent("instFlow", known.get("INST_FLOW"));
    known.putIfAbsent("rsIdx", known.get("RS_INDEX"));
    known.putIfAbsent("vol20", known.get("vol20d"));
    // advice-v6: secCons 는 파생 열(1/0)이라 특징 맵에 없다 — 인용되면 같은 정의로 대조한다
    Integer secCons = AdvicePromptBuilder.secCons(candidate.features());
    if (secCons != null) {
      known.putIfAbsent("secCons", secCons.doubleValue());
    }
    List<CitedFeature> result = new ArrayList<>();
    for (AdviceResponse.Cited c : cited) {
      if (c == null || c.name() == null) {
        continue;
      }
      Double actual = known.get(c.name());
      if (actual == null) {
        // 종목 특징이 아닌 시장·섹터 인용은 검증 대상 밖 — 기록만
        result.add(new CitedFeature(c.name(), c.value()));
        continue;
      }
      double tolerance = Math.max(CITED_ABS_TOLERANCE, Math.abs(actual) * CITED_REL_TOLERANCE);
      if (Math.abs(actual - c.value()) > tolerance) {
        increment(stats, "citedMismatch");
        return null;
      }
      result.add(new CitedFeature(c.name(), c.value()));
    }
    return result;
  }

  /**
   * 인용 헤드라인 검증: 입력에 없던 id 는 제거(unknownNews), 시장 헤드라인이 아니면서 이 종목에 태깅되지 않은 기사도 제거(newsMismatch). 중복 제거.
   */
  static List<String> checkCitedNews(List<String> cited, String ticker, Map<String, Set<String>> newsTickers, Map<String, Object> stats) {
    if (cited == null || cited.isEmpty()) {
      return List.of();
    }
    List<String> kept = new ArrayList<>();
    for (String id : cited) {
      if (id == null) {
        continue;
      }
      String key = id.trim();
      Set<String> tickers = newsTickers.get(key);
      if (tickers == null) {
        increment(stats, "unknownNews");
        continue;
      }
      if (!tickers.isEmpty() && !tickers.contains(ticker)) {
        increment(stats, "newsMismatch");
        continue;
      }
      if (!kept.contains(key)) {
        kept.add(key);
      }
    }
    return kept;
  }

  /**
   * 허용 이산값 중 가장 가까운 값. null·파싱 실패는 0.55.
   */
  static double conviction(String value, Map<String, Object> stats, String key) {
    double parsed;
    try {
      parsed = value == null ? Double.NaN : Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      parsed = Double.NaN;
    }
    if (Double.isNaN(parsed)) {
      increment(stats, key);
      return 0.55;
    }
    double best = 0.55;
    double bestDiff = Double.MAX_VALUE;
    for (String allowed : AdviceSchemaFactory.CONVICTIONS) {
      double a = Double.parseDouble(allowed);
      if (Math.abs(a - parsed) < bestDiff) {
        bestDiff = Math.abs(a - parsed);
        best = a;
      }
    }
    if (bestDiff > 1e-9) {
      increment(stats, key);
    }
    return best;
  }

  private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback, Map<String, Object> stats, String key) {
    if (value != null) {
      try {
        return Enum.valueOf(type, value.trim().toUpperCase());
      } catch (IllegalArgumentException ignored) {
        // 아래에서 기록
      }
    }
    increment(stats, key);
    return fallback;
  }

  /**
   * Slack 멘션·제어문자를 제거하고 길이를 자른다 (프롬프트 인젝션·채널 소음 방어).
   */
  static String sanitize(String text, int limit, Map<String, Object> stats) {
    if (text == null) {
      return null;
    }
    String cleaned = text.replace("<!channel>", "").replace("<!here>", "").replace("<!everyone>", "")
        .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").trim();
    if (!cleaned.equals(text.trim())) {
      increment(stats, "sanitized");
    }
    if (cleaned.length() > limit) {
      increment(stats, "truncatedText");
      cleaned = StringUtils.abbreviate(cleaned, limit);
    }
    return cleaned;
  }

  private static void increment(Map<String, Object> stats, String key) {
    stats.merge(key, 1, (a, b) -> ((Integer) a) + ((Integer) b));
  }
}

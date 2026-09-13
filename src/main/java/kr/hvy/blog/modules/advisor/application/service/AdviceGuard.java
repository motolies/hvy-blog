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
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CitedFeature;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import org.apache.commons.lang3.StringUtils;

/**
 * 판단 출력 검증(환각·범위·개수·인젝션). 스키마 enum 이 1차 방어, 여기가 2차 방어다.
 * <ul>
 *   <li>후보 밖 티커·중복·모르는 direction 은 제거</li>
 *   <li>확신값은 허용 목록 밖이면 가장 가까운 허용값으로, 국면 확신도 동일</li>
 *   <li>citedFeatures 의 값이 입력 특징과 허용오차(상대 2% 또는 절대 1e-4) 밖이면 픽 제거(근거 위조)</li>
 *   <li>픽 > max 는 확신 내림차순 상위만, AVOID 는 최대 2개, 픽 < min 이면 tooFew</li>
 *   <li>텍스트에서 <!channel>·<!here>·제어문자 제거, 길이 절단</li>
 * </ul>
 * 제거율(제거 픽 / 원본 픽)이 30% 를 넘으면 모델·프롬프트가 어긋난 신호로 보고 run 을 PARTIAL 로 둔다.
 */
public final class AdviceGuard {

  public static final double REMOVAL_ALERT_RATIO = 0.30;
  static final int MAX_AVOID = 2;
  static final int THESIS_LIMIT = 400;
  static final int RISK_LIMIT = 300;
  static final int RATIONALE_LIMIT = 900;
  static final int SUMMARY_LIMIT = 900;
  static final double CITED_REL_TOLERANCE = 0.02;
  static final double CITED_ABS_TOLERANCE = 1e-4;

  /** 검증 결과 */
  public record Result(MarketRegimeCode regime, DirectionCall kospiDir, DirectionCall kosdaqDir, double pUp, String rationale,
                       List<SectorCall> sectors, List<PickRow> picks, String summary, Map<String, Object> stats, int originalPicks, int removed) {

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

  public Result validate(AdviceResponse response, List<CandidateRow> candidates, Map<String, String> sectorNames) {
    Map<String, Object> stats = new LinkedHashMap<>();
    Map<String, CandidateRow> byTicker = new LinkedHashMap<>();
    candidates.forEach(c -> byTicker.put(c.ticker(), c));

    // ----- 국면 -----
    AdviceResponse.Regime r = response.regime();
    MarketRegimeCode regime = enumOr(MarketRegimeCode.class, r == null ? null : r.code(), MarketRegimeCode.NEUTRAL, stats, "badRegime");
    DirectionCall kospi = enumOr(DirectionCall.class, r == null ? null : r.kospiDir(), DirectionCall.NEUTRAL, stats, "badKospiDir");
    DirectionCall kosdaq = enumOr(DirectionCall.class, r == null ? null : r.kosdaqDir(), DirectionCall.NEUTRAL, stats, "badKosdaqDir");
    double pUp = conviction(r == null ? null : r.pUp(), stats, "clampedPUp");
    String rationale = sanitize(r == null ? null : r.rationale(), RATIONALE_LIMIT, stats);

    // ----- 섹터 -----
    List<SectorCall> sectors = new ArrayList<>();
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
        sectors.add(new SectorCall(s.code(), sectorNames.get(s.code()), sanitize(s.reason(), RISK_LIMIT, stats)));
        if (sectors.size() == 4) {
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
        List<CitedFeature> cited = checkCited(p.citedFeatures(), byTicker.get(p.ticker()), stats);
        if (cited == null) {
          removed++;
          continue;
        }
        picks.add(PickRow.builder()
            .ticker(p.ticker())
            .direction(direction)
            .conviction(conviction(p.conviction(), stats, "clampedConviction"))
            .thesis(sanitize(p.thesis(), THESIS_LIMIT, stats))
            .riskNote(sanitize(p.risk(), RISK_LIMIT, stats))
            .cited(cited)
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
    return new Result(regime, kospi, kosdaq, pUp, rationale, sectors, ranked, sanitize(response.summary(), SUMMARY_LIMIT, stats), stats,
        original, removed);
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

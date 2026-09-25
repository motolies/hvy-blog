package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.LongTermFactorRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;

/**
 * 장기(H60·H180) 규칙 점수(M8). 순수 함수 — DB·시계·난수 없이 같은 입력이면 같은 순위다(입력 행 순서와도 무관).
 * <pre>
 * 1. 팩터마다 유니버스 전체(값이 있는 행끼리) 횡단면 백분위 = PERCENT_RANK (동률은 최소 순위, n=1 이면 0) — 스크리닝·IC 와 같은 정의
 * 2. 점수 = Σ w·s / Σ w,  s = ±(2·pct − 1) (낮을수록 좋은 팩터는 부호 반전), 결측 팩터는 s = 0(중립)
 * 3. 커버리지 = 값이 있는 팩터의 w 합 / 전체 w 합 — min-coverage 미만이면 후보 제외(재무가 전부 없는 종목이 모멘텀 하나로 뽑히지 않게)
 * 4. 후보 필터: 픽 유니버스(KOSPI200 PIT, ALL 이면 전부) — 백분위를 매긴 뒤에 거른다(모집단을 바꾸면 척도가 바뀐다)
 * 5. 점수 내림차순 → 티커 오름차순(동률 결정), 섹터당 maxPerSector(섹터 없음은 한 그룹 "-") → 상위 candidateLimit 가 후보, 그 앞 pickCount 가 픽
 * </pre>
 */
public final class LongTermScorer {

  /** 섹터 없는 종목의 섹터 상한 그룹 키 (CandidateScreeningService 의 COALESCE(sector_code, '-') 와 같다) */
  static final String NO_SECTOR = "-";

  /**
   * 규칙 결과. candidates 는 점수 순(quantRank 1 부터), pickTickers 는 그 앞 N 개의 티커(순서 = 순위).
   *
   * @param universeSize 백분위 모집단 수
   * @param eligibleSize 커버리지·픽 유니버스 필터 통과 수(섹터 상한 전)
   */
  public record Ranking(int universeSize, int eligibleSize, List<CandidateRow> candidates, List<String> pickTickers) {
  }

  /**
   * 점수·필터 설정.
   *
   * @param weights        팩터 코드 → 사전 고정 가중치 (0 인 팩터는 점수·커버리지에서 빠진다)
   * @param allUniverse    true 면 픽 유니버스 필터 없음(ALL), false 면 KOSPI200 구성(PIT)만
   */
  public record Rule(Map<String, Double> weights, boolean allUniverse, double minCoverage, int candidateLimit, int pickCount, int maxPerSector) {
  }

  private LongTermScorer() {
  }

  /**
   * 규칙 순위를 매긴다.
   */
  public static Ranking rank(List<LongTermFactorRow> rows, Rule rule) {
    List<SignalCode> factors = SignalCode.longTerm().stream().filter(f -> rule.weights().getOrDefault(f.getCode(), 0.0) > 0).toList();
    double weightSum = factors.stream().mapToDouble(f -> rule.weights().get(f.getCode())).sum();
    Map<SignalCode, Map<String, Double>> pct = new LinkedHashMap<>();
    for (SignalCode f : factors) {
      pct.put(f, percentRanks(rows, f));
    }

    List<Scored> eligible = new ArrayList<>();
    for (LongTermFactorRow row : rows) {
      double numerator = 0;
      double covered = 0;
      Map<String, SignalValue> signals = new LinkedHashMap<>();
      for (SignalCode f : factors) {
        double w = rule.weights().get(f.getCode());
        Double raw = row.factors() == null ? null : row.factors().get(f);
        Double p = pct.get(f).get(row.ticker());
        if (raw != null && p != null) {
          numerator += w * f.sign() * (2 * p - 1);
          covered += w;
        }
        signals.put(f.getCode(), new SignalValue(p == null ? 0.5 : round4(p), w, raw == null ? null : round6(raw)));
      }
      double coverage = weightSum == 0 ? 0 : covered / weightSum;
      boolean inUniverse = rule.allUniverse() || Boolean.TRUE.equals(row.kospi200());
      if (inUniverse && coverage + 1e-12 >= rule.minCoverage()) {
        eligible.add(new Scored(row, weightSum == 0 ? 0 : round6(numerator / weightSum), round4(coverage), signals));
      }
    }
    eligible.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.row().ticker()));

    List<CandidateRow> candidates = new ArrayList<>();
    Map<String, Integer> perSector = new HashMap<>();
    for (Scored s : eligible) {
      if (candidates.size() >= rule.candidateLimit()) {
        break;
      }
      String sector = s.row().sectorCode() == null ? NO_SECTOR : s.row().sectorCode();
      if (perSector.merge(sector, 1, Integer::sum) > rule.maxPerSector()) {
        continue;
      }
      candidates.add(candidate(s, candidates.size() + 1));
    }
    List<String> picks = candidates.stream().limit(rule.pickCount()).map(CandidateRow::ticker).toList();
    return new Ranking(rows.size(), eligible.size(), candidates, picks);
  }

  /**
   * 팩터 f 의 PERCENT_RANK (값이 있는 행만, 오름차순). 동률은 가장 앞 순위를 공유하고, 표본이 1개면 0.
   */
  static Map<String, Double> percentRanks(List<LongTermFactorRow> rows, SignalCode f) {
    List<LongTermFactorRow> valued = rows.stream()
        .filter(r -> r.factors() != null && r.factors().get(f) != null && Double.isFinite(r.factors().get(f)))
        .sorted(Comparator.comparingDouble((LongTermFactorRow r) -> r.factors().get(f)).thenComparing(LongTermFactorRow::ticker))
        .toList();
    Map<String, Double> result = new HashMap<>();
    int n = valued.size();
    int rankStart = 0;
    for (int i = 0; i < n; i++) {
      if (i > 0 && Double.compare(valued.get(i).factors().get(f), valued.get(i - 1).factors().get(f)) != 0) {
        rankStart = i;
      }
      result.put(valued.get(i).ticker(), n <= 1 ? 0.0 : (double) rankStart / (n - 1));
    }
    return result;
  }

  /**
   * 후보 스냅샷 1행: 점수·팩터 백분위/가중치/원값·테마·커버리지·재무 결산기. 벤치 지수는 시장별(KOSPI 0001, 그 외 1001) — 채점이 이 코드로 초과수익을 낸다.
   */
  private static CandidateRow candidate(Scored s, int rank) {
    LongTermFactorRow r = s.row();
    Map<String, Object> features = new LinkedHashMap<>();
    if (r.adjClose() != null) {
      features.put("close", round6(r.adjClose()));
    }
    features.put("coverage", s.coverage());
    if (r.k200Sector() != null) {
      features.put("theme", r.k200Sector());
    }
    if (r.fiscalPeriod() != null) {
      features.put("fiscalPeriod", r.fiscalPeriod());
    }
    return CandidateRow.builder()
        .ticker(r.ticker())
        .quantRank(rank)
        .quantScore(s.score())
        .stockName(r.stockName())
        .marketType(r.marketType())
        .benchIndexCode("KOSPI".equals(r.marketType()) ? "0001" : "1001")
        .sectorCode(r.sectorCode())
        .sectorName(r.sectorName())
        .signals(s.signals())
        .features(features)
        .appliedLessonIds(List.of())
        .refRawClose(r.rawClose())
        .refAdjClose(r.adjClose())
        .build();
  }

  private record Scored(LongTermFactorRow row, double score, double coverage, Map<String, SignalValue> signals) {
  }

  static double round4(double v) {
    return Math.round(v * 1e4) / 1e4;
  }

  static double round6(double v) {
    return Math.round(v * 1e6) / 1e6;
  }
}

package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.LessonProposalResponse;
import kr.hvy.blog.modules.advisor.domain.code.LessonScope;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.LessonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 교훈(프롬프트 메모리) 규율. 저장·활성·폐기는 전부 통계 규칙이고 LLM 은 후보 문안을 제안할 뿐이다.
 * <ul>
 *   <li>셀 집계: (국면 × 시그널 버킷 HIGH/LOW × 섹터) 누적 LIVE LONG 픽 초과수익, n ≥ min-cell-samples 만 생성기에 준다(최근성 편향 차단)</li>
 *   <li>저장 거부: condition 형식 불량, evidence 없음·n < 20·|t| < 2, 종목코드(6자리) 언급, 같은 scope+condition 의 활성 교훈 존재</li>
 *   <li>활성 ≤ active-limit. 초과분은 CANDIDATE 로 남긴다</li>
 *   <li>폐기: 활성 후 review-weeks 경과 또는 적용 픽 ≥ review-picks 시점에 (적용 − 비적용 평균 초과수익) ≤ 0</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LessonService {

  static final Pattern TICKER_MENTION = Pattern.compile("(?<![0-9A-Za-z])\\d{6}(?![0-9])");
  static final double HIGH_PCT = 0.8;
  static final double LOW_PCT = 0.2;

  /** 집계 셀 */
  public record Cell(String regime, String signal, String bucket, String sector, int n, double meanExcess, double tStat, LocalDate from, LocalDate to) {
  }

  /** 제안 적용 결과 */
  public record Applied(List<Long> activated, List<Long> candidates, List<String> rejected, List<Long> retired) {
  }

  private final NamedParameterJdbcTemplate jdbc;
  private final LessonRepository lessons;
  private final AdvisorProperties properties;

  /**
   * 누적 LIVE LONG 픽(h=결정 호라이즌, 품질 OK)을 셀로 집계한다.
   */
  public List<Cell> cells(LocalDate from, LocalDate to) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT a.regime_code, c.sector_code, c.signal_json::text AS signal_json, s.excess_ret, a.base_date
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate c ON c.advice_id = pk.advice_id AND c.ticker = pk.ticker
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.variant = 'LIVE' AND a.data_quality = 'OK' AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
          AND a.base_date BETWEEN :from AND :to
        """, Map.of("h", properties.getHorizonDays(), "from", from, "to", to));
    Map<String, List<double[]>> groups = new LinkedHashMap<>();
    Map<String, LocalDate[]> ranges = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      String regime = r.get("regime_code") == null ? null : String.valueOf(r.get("regime_code"));
      String sector = r.get("sector_code") == null ? null : String.valueOf(r.get("sector_code"));
      double excess = ((Number) r.get("excess_ret")).doubleValue();
      LocalDate date = ((java.sql.Date) r.get("base_date")).toLocalDate();
      Map<String, Object> signals = AdvisorJson.readMap(String.valueOf(r.get("signal_json")));
      add(groups, ranges, key(regime, null, null, null), excess, date);
      add(groups, ranges, key(regime, null, null, sector), excess, date);
      for (Map.Entry<String, Object> e : signals.entrySet()) {
        if (e.getValue() instanceof Map<?, ?> v && v.get("pct") instanceof Number pct) {
          String bucket = pct.doubleValue() >= HIGH_PCT ? "HIGH" : pct.doubleValue() <= LOW_PCT ? "LOW" : null;
          if (bucket != null) {
            add(groups, ranges, key(regime, e.getKey(), bucket, null), excess, date);
          }
        }
      }
    }
    List<Cell> cells = new ArrayList<>();
    for (Map.Entry<String, List<double[]>> e : groups.entrySet()) {
      List<double[]> values = e.getValue();
      int n = values.size();
      if (n < properties.getLesson().getMinCellSamples()) {
        continue;
      }
      double mean = values.stream().mapToDouble(v -> v[0]).average().orElse(0);
      double var = n < 2 ? 0 : values.stream().mapToDouble(v -> (v[0] - mean) * (v[0] - mean)).sum() / (n - 1);
      double se = Math.sqrt(var / n);
      double t = se > 0 ? mean / se : 0;
      String[] k = e.getKey().split("\\|", -1);
      LocalDate[] range = ranges.get(e.getKey());
      cells.add(new Cell(blankToNull(k[0]), blankToNull(k[1]), blankToNull(k[2]), blankToNull(k[3]), n, mean, t, range[0], range[1]));
    }
    return cells;
  }

  /**
   * 생성기 입력: 셀 표·보정 표·활성 교훈 사후 성과·데이터 품질. 종목명·근거 텍스트·뉴스는 주지 않는다.
   */
  public Map<String, Object> reviewPayload(List<Cell> cells, List<AdvisorKpiService.CalibrationRow> calibration, double degradedRatio) {
    Map<String, Object> payload = new LinkedHashMap<>();
    List<Map<String, Object>> cellRows = new ArrayList<>();
    for (Cell c : cells) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("regime", c.regime());
      m.put("signal", c.signal());
      m.put("bucket", c.bucket());
      m.put("sector", c.sector());
      m.put("n", c.n());
      m.put("excess", round(c.meanExcess()));
      m.put("t", round(c.tStat()));
      m.put("from", c.from().toString());
      m.put("to", c.to().toString());
      cellRows.add(m);
    }
    payload.put("cells", cellRows);
    List<Map<String, Object>> cal = new ArrayList<>();
    for (AdvisorKpiService.CalibrationRow r : calibration) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("conviction", r.conviction());
      m.put("n", r.n());
      m.put("realizedHitRate", r.hitRate() == null ? null : round(r.hitRate()));
      cal.add(m);
    }
    payload.put("calibration", cal);
    List<Map<String, Object>> active = new ArrayList<>();
    for (LessonRow l : lessons.findByStatus(LessonStatus.ACTIVE)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("lessonId", l.lessonId());
      m.put("condition", l.condition());
      m.put("rule", l.rule());
      m.put("postNApplied", l.postNApplied());
      m.put("postExcessApplied", l.postExcessApplied() == null ? null : round(l.postExcessApplied()));
      m.put("postNNotApplied", l.postNNotApplied());
      m.put("postExcessNotApplied", l.postExcessNotApplied() == null ? null : round(l.postExcessNotApplied()));
      active.add(m);
    }
    payload.put("activeLessons", active);
    payload.put("dataQuality", Map.of("degradedRatio", round(degradedRatio)));
    payload.put("rules", Map.of("minN", properties.getLesson().getMinCellSamples(), "minAbsT", properties.getLesson().getMinTStat()));
    return payload;
  }

  /**
   * 제안을 검증해 저장한다. 통과한 제안은 활성 슬롯이 남으면 ACTIVE, 아니면 CANDIDATE.
   */
  public Applied apply(LessonProposalResponse response, Long runId, String model, Instant now) {
    List<Long> activated = new ArrayList<>();
    List<Long> candidates = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    List<LessonRow> active = new ArrayList<>(lessons.findByStatus(LessonStatus.ACTIVE));
    if (response != null && response.proposals() != null) {
      for (LessonProposalResponse.Proposal p : response.proposals()) {
        String reason = reject(p, active);
        if (reason != null) {
          rejected.add(reason);
          continue;
        }
        Map<String, Object> condition = condition(p.condition());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("n", p.evidence().n());
        evidence.put("from", p.evidence().from());
        evidence.put("to", p.evidence().to());
        evidence.put("excess", p.evidence().excess());
        evidence.put("t", p.evidence().t());
        boolean slot = active.size() < properties.getLesson().getActiveLimit();
        LessonRow row = LessonRow.builder()
            .status(slot ? LessonStatus.ACTIVE : LessonStatus.CANDIDATE)
            .scope(LessonScope.valueOf(p.scope()))
            .condition(condition)
            .observation(StringUtils.abbreviate(p.observation().trim(), 300))
            .evidence(evidence)
            .rule(StringUtils.abbreviate(p.rule().trim(), 300))
            .lessonText(render(p))
            .appliedCount(0)
            .activatedAt(slot ? now : null)
            .model(model)
            .runId(runId)
            .build();
        long id = lessons.insert(row);
        if (slot) {
          activated.add(id);
          active.add(row.toBuilder().lessonId(id).build());
        } else {
          candidates.add(id);
        }
      }
    }
    return new Applied(activated, candidates, rejected, List.of());
  }

  /**
   * 활성 교훈의 사후 성과를 갱신하고 폐기 규칙을 적용한다. 폐기된 id 목록을 돌려준다.
   */
  public List<Long> review(Instant now) {
    List<Long> retired = new ArrayList<>();
    AdvisorProperties.Lesson cfg = properties.getLesson();
    for (LessonRow lesson : lessons.findByStatus(LessonStatus.ACTIVE)) {
      double[] post = postPerformance(lesson);
      int nApplied = (int) post[0];
      int nNot = (int) post[2];
      Double excessApplied = nApplied == 0 ? null : post[1];
      Double excessNot = nNot == 0 ? null : post[3];
      lessons.updatePostPerformance(lesson.lessonId(), nApplied, excessApplied, nNot, excessNot);
      boolean due = lesson.activatedAt() != null && (ChronoUnit.DAYS.between(lesson.activatedAt(), now) >= cfg.getReviewWeeks() * 7L
          || nApplied >= cfg.getReviewPicks());
      if (due && (excessApplied == null || excessNot == null || excessApplied - excessNot <= 0)) {
        lessons.updateStatus(lesson.lessonId(), LessonStatus.RETIRED, now, String.format("검토 시점 적용 %d건 %.4f vs 비적용 %d건 %.4f — 개선 없음",
            nApplied, excessApplied == null ? 0 : excessApplied, nNot, excessNot == null ? 0 : excessNot));
        retired.add(lesson.lessonId());
      }
    }
    // 빈 슬롯은 후보 중 |t| 큰 순으로 승격
    List<LessonRow> active = lessons.findByStatus(LessonStatus.ACTIVE);
    int slots = cfg.getActiveLimit() - active.size();
    if (slots > 0) {
      List<LessonRow> candidates = new ArrayList<>(lessons.findByStatus(LessonStatus.CANDIDATE));
      candidates.sort((a, b) -> Double.compare(absT(b), absT(a)));
      for (LessonRow c : candidates.subList(0, Math.min(slots, candidates.size()))) {
        lessons.updateStatus(c.lessonId(), LessonStatus.ACTIVE, now, null);
      }
    }
    return retired;
  }

  /**
   * 활성 후 LIVE LONG 픽을 적용/비적용으로 나눠 {n_applied, mean_applied, n_not, mean_not}.
   */
  double[] postPerformance(LessonRow lesson) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT (COALESCE(c.applied_lesson_ids, '[]'::jsonb) @> CAST(:id AS jsonb)) AS applied, s.excess_ret
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate c ON c.advice_id = pk.advice_id AND c.ticker = pk.ticker
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.variant = 'LIVE' AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
          AND a.created_at >= :activatedAt
        """, Map.of("id", "[" + lesson.lessonId() + "]", "h", properties.getHorizonDays(),
        "activatedAt", lesson.activatedAt() == null ? java.time.OffsetDateTime.now() : lesson.activatedAt().atOffset(java.time.ZoneOffset.UTC)));
    double sumApplied = 0;
    int nApplied = 0;
    double sumNot = 0;
    int nNot = 0;
    for (Map<String, Object> r : rows) {
      double excess = ((Number) r.get("excess_ret")).doubleValue();
      if (Boolean.TRUE.equals(r.get("applied"))) {
        sumApplied += excess;
        nApplied++;
      } else {
        sumNot += excess;
        nNot++;
      }
    }
    return new double[] {nApplied, nApplied == 0 ? 0 : sumApplied / nApplied, nNot, nNot == 0 ? 0 : sumNot / nNot};
  }

  /**
   * 거부 사유 (통과면 null).
   */
  String reject(LessonProposalResponse.Proposal p, List<LessonRow> active) {
    if (p == null || p.scope() == null || p.condition() == null || p.evidence() == null || p.rule() == null || p.observation() == null) {
      return "필드 누락";
    }
    try {
      LessonScope.valueOf(p.scope());
    } catch (IllegalArgumentException e) {
      return "scope 불량: " + p.scope();
    }
    Map<String, Object> condition = condition(p.condition());
    if (!LessonCondition.isWellFormed(condition)) {
      return "condition 형식 불량: " + condition;
    }
    AdvisorProperties.Lesson cfg = properties.getLesson();
    if (p.evidence().n() == null || p.evidence().n() < cfg.getMinCellSamples() || p.evidence().t() == null
        || Math.abs(p.evidence().t()) < cfg.getMinTStat()) {
      return String.format("근거 부족: n=%s t=%s", p.evidence().n(), p.evidence().t());
    }
    if (TICKER_MENTION.matcher(p.observation() + " " + p.rule()).find()) {
      return "종목코드 언급";
    }
    for (LessonRow a : active) {
      if (a.scope().name().equals(p.scope()) && Objects.equals(normalize(a.condition()), normalize(condition))) {
        return "같은 조건의 활성 교훈 존재: " + a.lessonId();
      }
    }
    return null;
  }

  static Map<String, Object> condition(LessonProposalResponse.Condition c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("regime", c.regime());
    m.put("trend", c.trend());
    m.put("signal", c.signal());
    m.put("op", c.op());
    m.put("pct", c.pct());
    m.put("sector", c.sector());
    m.values().removeIf(Objects::isNull);
    return m;
  }

  static Map<String, Object> normalize(Map<String, Object> condition) {
    Map<String, Object> m = new java.util.TreeMap<>();
    condition.forEach((k, v) -> {
      if (v != null) {
        m.put(k, v instanceof Number n ? n.doubleValue() : String.valueOf(v));
      }
    });
    return m;
  }

  /**
   * 프롬프트에 넣는 텍스트: [관찰] … (n, t) [규칙] …
   */
  static String render(LessonProposalResponse.Proposal p) {
    return StringUtils.abbreviate(String.format("[관찰] %s (n=%d, t=%.1f, %s~%s) [규칙] %s", p.observation().trim(), p.evidence().n(),
        p.evidence().t(), p.evidence().from(), p.evidence().to(), p.rule().trim()), 600);
  }

  private static double absT(LessonRow row) {
    Object t = row.evidence() == null ? null : row.evidence().get("t");
    return t instanceof Number n ? Math.abs(n.doubleValue()) : 0;
  }

  private static void add(Map<String, List<double[]>> groups, Map<String, LocalDate[]> ranges, String key, double excess, LocalDate date) {
    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[] {excess});
    LocalDate[] range = ranges.computeIfAbsent(key, k -> new LocalDate[] {date, date});
    if (date.isBefore(range[0])) {
      range[0] = date;
    }
    if (date.isAfter(range[1])) {
      range[1] = date;
    }
  }

  private static String key(String regime, String signal, String bucket, String sector) {
    return String.join("|", nullToBlank(regime), nullToBlank(signal), nullToBlank(bucket), nullToBlank(sector));
  }

  private static String nullToBlank(String s) {
    return s == null ? "" : s;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static double round(double v) {
    return Math.round(v * 1e4) / 1e4;
  }
}

package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * KPI 집계 (결정 호라이즌만). 시그널 층은 IC, LLM 층은 픽 평균 초과수익 − 후보군 평균 초과수익 ± se 가 1차 KPI 이고 승률은 보조, Brier 는 보정 전용.
 * <p>
 * 학습·KPI 는 LONG 픽만(AVOID 는 별도 집계), data_quality=OK 인 판단만 센다. 셀 t>2 는 우연으로 ≈5% 나오므로 판정은 최소 6개월 뒤에.
 */
@Service
@RequiredArgsConstructor
public class AdvisorKpiService {

  /** 변형 1개의 요약 */
  public record VariantSummary(AdviceVariant variant, int advices, int picks, Double hitRate, Double meanExcess, Double seExcess, Double meanCostAdj,
                               Double poolMeanExcess, Double valueAdd, Double avoidMeanExcess, int avoidPicks) {
  }

  /** 국면 콜 요약 */
  public record RegimeSummary(int calls, Double hitRate, Double meanBrier, Double brierSkill) {
  }

  /** 신뢰도 버킷 보정 행 */
  public record CalibrationRow(double conviction, int n, Double hitRate, Double meanExcess) {
  }

  /** 최근 채점 1건 (Slack 표시용) */
  public record RecentPick(LocalDate baseDate, String ticker, String stockName, double conviction, Double excess, Boolean hit) {
  }

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;

  public List<VariantSummary> variantSummaries(LocalDate from, LocalDate to) {
    List<VariantSummary> result = new ArrayList<>();
    for (AdviceVariant variant : AdviceVariant.values()) {
      result.add(variantSummary(variant, from, to));
    }
    return result;
  }

  /**
   * 변형별 픽 KPI: 승률(초과수익>0), 평균 초과수익 ± se, 비용 차감 평균, 후보군 평균, 부가가치(픽 − 후보군).
   */
  public VariantSummary variantSummary(AdviceVariant variant, LocalDate from, LocalDate to) {
    Map<String, Object> p = params(from, to);
    p.put("variant", variant.getCode());
    Map<String, Object> pick = jdbc.queryForMap("""
        SELECT COUNT(DISTINCT a.advice_id) AS advices, COUNT(*) AS n,
               AVG(CASE WHEN s.excess_ret > 0 THEN 1.0 ELSE 0.0 END) AS hit_rate,
               AVG(s.excess_ret) AS mean_excess, STDDEV_SAMP(s.excess_ret) AS sd_excess, AVG(s.cost_adj_excess) AS mean_cost_adj
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.variant = :variant AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        """, p);
    Map<String, Object> avoid = jdbc.queryForMap("""
        SELECT COUNT(*) AS n, AVG(s.excess_ret) AS mean_excess
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.variant = :variant AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND pk.direction = 'AVOID' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        """, p);
    Map<String, Object> pool = jdbc.queryForMap("""
        SELECT AVG(s.excess_ret) AS mean_excess
        FROM tb_advisor_candidate_score s
                 JOIN tb_advisor_advice a ON a.advice_id = s.advice_id
        WHERE a.variant = :variant AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND s.horizon_days = :h AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        """, p);
    int n = ((Number) pick.get("n")).intValue();
    Double mean = d(pick.get("mean_excess"));
    Double sd = d(pick.get("sd_excess"));
    Double se = mean == null || sd == null || n < 2 ? null : sd / Math.sqrt(n);
    Double poolMean = d(pool.get("mean_excess"));
    return new VariantSummary(variant, ((Number) pick.get("advices")).intValue(), n, d(pick.get("hit_rate")), mean, se, d(pick.get("mean_cost_adj")),
        poolMean, mean == null || poolMean == null ? null : mean - poolMean, d(avoid.get("mean_excess")), ((Number) avoid.get("n")).intValue());
  }

  /**
   * 국면(지수 방향) 콜 요약: 적중률·평균 Brier·Brier skill = 1 − Brier/0.25.
   */
  public RegimeSummary regimeSummary(AdviceVariant variant, LocalDate from, LocalDate to) {
    Map<String, Object> p = params(from, to);
    p.put("variant", variant.getCode());
    Map<String, Object> row = jdbc.queryForMap("""
        SELECT COUNT(*) AS n, AVG(CASE WHEN c.hit THEN 1.0 ELSE 0.0 END) AS hit_rate, AVG(c.brier) AS brier
        FROM tb_advisor_call_score c JOIN tb_advisor_advice a ON a.advice_id = c.advice_id
        WHERE a.variant = :variant AND a.base_date BETWEEN :from AND :to AND c.subject_type = 'INDEX' AND c.horizon_days = :h AND c.status = 'SCORED'
        """, p);
    Double brier = d(row.get("brier"));
    return new RegimeSummary(((Number) row.get("n")).intValue(), d(row.get("hit_rate")), brier, brier == null ? null : 1 - brier / 0.25);
  }

  /** 추세 전망 콜 요약 (h = trend.score-horizon-days): 지속 버킷 적중률·Brier(적중 기준), 무효화 신호 적중률 */
  public record TrendSummary(int calls, Double hitRate, Double meanBrier, int invalidationCalls, Double invalidationHitRate) {
  }

  /**
   * 추세 지속(TREND)·무효화(TREND_INV) 콜 요약. INDEX 의 국면 요약과 Brier 정의가 달라 따로 보고한다.
   */
  public TrendSummary trendSummary(AdviceVariant variant, LocalDate from, LocalDate to) {
    Map<String, Object> p = params(from, to);
    p.put("variant", variant.getCode());
    p.put("th", properties.getTrend().getScoreHorizonDays());
    Map<String, Object> row = jdbc.queryForMap("""
        SELECT COUNT(*) FILTER (WHERE c.subject_type = 'TREND') AS n,
               AVG(CASE WHEN c.hit THEN 1.0 ELSE 0.0 END) FILTER (WHERE c.subject_type = 'TREND') AS hit_rate,
               AVG(c.brier) FILTER (WHERE c.subject_type = 'TREND') AS brier,
               COUNT(*) FILTER (WHERE c.subject_type = 'TREND_INV') AS inv_n,
               AVG(CASE WHEN c.hit THEN 1.0 ELSE 0.0 END) FILTER (WHERE c.subject_type = 'TREND_INV') AS inv_hit_rate
        FROM tb_advisor_call_score c JOIN tb_advisor_advice a ON a.advice_id = c.advice_id
        WHERE a.variant = :variant AND a.base_date BETWEEN :from AND :to AND c.subject_type IN ('TREND', 'TREND_INV')
          AND c.horizon_days = :th AND c.status = 'SCORED'
        """, p);
    return new TrendSummary(((Number) row.get("n")).intValue(), d(row.get("hit_rate")), d(row.get("brier")),
        ((Number) row.get("inv_n")).intValue(), d(row.get("inv_hit_rate")));
  }

  /** 아침 점검 갭 판정 요약 (h=1): n·적중률·CAUTION 비율 */
  public record MorningSummary(int calls, Double hitRate, Double cautionRate) {
  }

  /**
   * 아침 점검(MORNING) 콜 요약 — 예상 갭 부호·크기 판정이 D+1 시가 갭과 맞은 비율.
   */
  public MorningSummary morningSummary(AdviceVariant variant, LocalDate from, LocalDate to) {
    Map<String, Object> p = params(from, to);
    p.put("variant", variant.getCode());
    Map<String, Object> row = jdbc.queryForMap("""
        SELECT COUNT(*) AS n, AVG(CASE WHEN c.hit THEN 1.0 ELSE 0.0 END) AS hit_rate,
               AVG(CASE WHEN c.predicted = 'CAUTION' THEN 1.0 ELSE 0.0 END) AS caution_rate
        FROM tb_advisor_call_score c JOIN tb_advisor_advice a ON a.advice_id = c.advice_id
        WHERE a.variant = :variant AND a.base_date BETWEEN :from AND :to AND c.subject_type = 'MORNING' AND c.horizon_days = 1 AND c.status = 'SCORED'
        """, p);
    return new MorningSummary(((Number) row.get("n")).intValue(), d(row.get("hit_rate")), d(row.get("caution_rate")));
  }

  /**
   * 신뢰도 버킷별 보정 표 (LIVE, LONG).
   */
  public List<CalibrationRow> calibration(LocalDate from, LocalDate to) {
    Map<String, Object> p = params(from, to);
    return jdbc.query("""
        SELECT pk.conviction, COUNT(*) AS n, AVG(CASE WHEN s.excess_ret > 0 THEN 1.0 ELSE 0.0 END) AS hit_rate, AVG(s.excess_ret) AS mean_excess
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.variant = 'LIVE' AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        GROUP BY pk.conviction ORDER BY pk.conviction
        """, p, (rs, i) -> new CalibrationRow(rs.getDouble("conviction"), rs.getInt("n"), d(rs.getObject("hit_rate")), d(rs.getObject("mean_excess"))));
  }

  /**
   * 최근 채점된 LIVE 픽 (최신순).
   */
  public List<RecentPick> recentPicks(LocalDate from, LocalDate to, int limit) {
    Map<String, Object> p = params(from, to);
    p.put("limit", limit);
    return jdbc.query("""
        SELECT a.base_date, pk.ticker, c.stock_name, pk.conviction, s.excess_ret
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate c ON c.advice_id = pk.advice_id AND c.ticker = pk.ticker
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.variant = 'LIVE' AND a.base_date BETWEEN :from AND :to AND pk.direction = 'LONG' AND s.status <> 'MISSING'
        ORDER BY a.base_date DESC, pk.pick_rank LIMIT :limit
        """, p, (rs, i) -> {
      Double excess = d(rs.getObject("excess_ret"));
      return new RecentPick(rs.getObject("base_date", LocalDate.class), rs.getString("ticker"), rs.getString("stock_name"), rs.getDouble("conviction"),
          excess, excess == null ? null : excess > 0);
    });
  }

  /**
   * 프롬프트 실적 블록 (≤300 토큰 목표): 최근 창의 n·승률·초과수익±se·부가가치·국면 적중·보정 표. 종목명은 넣지 않는다(티커 애착 방지).
   */
  public Map<String, Object> promptScoreboard(LocalDate from, LocalDate to) {
    VariantSummary live = variantSummary(AdviceVariant.LIVE, from, to);
    RegimeSummary regime = regimeSummary(AdviceVariant.LIVE, from, to);
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("window", from + "~" + to);
    Map<String, Object> picks = new LinkedHashMap<>();
    picks.put("n", live.picks());
    picks.put("hitRate", round(live.hitRate()));
    picks.put("meanExcess", round(live.meanExcess()));
    picks.put("seExcess", round(live.seExcess()));
    picks.put("valueAddVsPool", round(live.valueAdd()));
    block.put("picks", picks);
    Map<String, Object> reg = new LinkedHashMap<>();
    reg.put("n", regime.calls());
    reg.put("hitRate", round(regime.hitRate()));
    reg.put("brierSkill", round(regime.brierSkill()));
    block.put("regime", reg);
    List<Map<String, Object>> cal = new ArrayList<>();
    for (CalibrationRow r : calibration(from, to)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("conviction", r.conviction());
      row.put("n", r.n());
      row.put("realizedHitRate", round(r.hitRate()));
      cal.add(row);
    }
    block.put("calibration", cal);
    return block;
  }

  /**
   * Slack 스코어보드 줄 (전일 채점·최근 창 요약).
   */
  public List<String> slackLines(LocalDate from, LocalDate to) {
    VariantSummary live = variantSummary(AdviceVariant.LIVE, from, to);
    if (live.picks() == 0) {
      return List.of();
    }
    RegimeSummary regime = regimeSummary(AdviceVariant.LIVE, from, to);
    List<String> lines = new ArrayList<>();
    lines.add(String.format("%d일 픽 %d개: 승률 %.1f%% · 평균 초과 %+.2f%%p (±%.2f) · 후보군 대비 %+.2f%%p",
        properties.getHorizonDays(), live.picks(), pct(live.hitRate()), pct(live.meanExcess()), pct(live.seExcess()), pct(live.valueAdd())));
    if (regime.calls() > 0) {
      lines.add(String.format("국면 적중 %.0f%% (n=%d) · Brier skill %.2f", pct(regime.hitRate()), regime.calls(), nz(regime.brierSkill())));
    }
    List<RecentPick> recent = recentPicks(from, to, 3);
    if (!recent.isEmpty()) {
      StringBuilder sb = new StringBuilder("최근: ");
      for (RecentPick r : recent) {
        sb.append(String.format("%s %s %+.1f%%  ", r.baseDate().getMonthValue() + "/" + r.baseDate().getDayOfMonth(),
            r.stockName() == null ? r.ticker() : r.stockName(), pct(r.excess())));
      }
      lines.add(sb.toString().trim());
    }
    return lines;
  }

  private Map<String, Object> params(LocalDate from, LocalDate to) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("from", from);
    p.put("to", to);
    p.put("h", properties.getHorizonDays());
    return p;
  }

  private static Double d(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }

  private static Double round(Double v) {
    return v == null ? null : Math.round(v * 1e4) / 1e4;
  }

  private static double pct(Double v) {
    return v == null ? 0.0 : v * 100;
  }

  private static double nz(Double v) {
    return v == null ? 0.0 : v;
  }
}

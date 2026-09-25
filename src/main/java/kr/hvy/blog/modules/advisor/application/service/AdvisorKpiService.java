package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * KPI 집계 (결정 호라이즌만). 시그널 층은 IC, LLM 층은 픽 평균 초과수익 − 후보군 평균 초과수익 ± se 가 1차 KPI 이고 승률은 보조, Brier 는 보정 전용.
 * <p>
 * 학습·KPI 는 LONG 픽만(AVOID 는 별도 집계), data_quality=OK 인 판단만 센다. 셀 t>2 는 우연으로 ≈5% 나오므로 판정은 최소 6개월 뒤에.
 * <p>
 * 멀티 호라이즌(M5): 변형 요약·국면·보정·최근 픽은 (종류, 호라이즌) 을 받는 버전이 있다 — 인자 없는 버전은 DAILY·결정 호라이즌(5)으로 기존 결과와 같다.
 * 프롬프트 실적 블록·Slack 스코어보드·교훈 입력(보정 표)·300 게이트는 계속 DAILY 만 본다.
 */
@Service
@RequiredArgsConstructor
public class AdvisorKpiService {

  /**
   * 판정 불가 라벨(M8): 가중치를 학습하지 않는(learn=false) 호라이즌의 판단 — 60·180거래일은 격주·월간 표본에 겹치는 코호트라 n_eff 가 작아 2년 안에 판정할 수 없다.
   * Slack(LongTermAdviceMessage)과 KPI 요약(VariantSummary.verdictLabel)이 같은 문구를 쓴다.
   */
  public static final String UNJUDGEABLE_LABEL = "판정 불가: 표본 부족, 2년 이상 필요";

  /**
   * 변형 1개의 요약. verdictLabel(M8)은 판정 불가 종류(H60·H180)일 때만 {@link #UNJUDGEABLE_LABEL}, 그 밖은 null — 컴포넌트는 맨 뒤에 추가했다.
   */
  public record VariantSummary(AdviceVariant variant, int advices, int picks, Double hitRate, Double meanExcess, Double seExcess, Double meanCostAdj,
                               Double poolMeanExcess, Double valueAdd, Double avoidMeanExcess, int avoidPicks, String verdictLabel) {

    /** M8 이전 모양(라벨 없음) — 기존 호출·테스트 호환용 */
    public VariantSummary(AdviceVariant variant, int advices, int picks, Double hitRate, Double meanExcess, Double seExcess, Double meanCostAdj,
        Double poolMeanExcess, Double valueAdd, Double avoidMeanExcess, int avoidPicks) {
      this(variant, advices, picks, hitRate, meanExcess, seExcess, meanCostAdj, poolMeanExcess, valueAdd, avoidMeanExcess, avoidPicks, null);
    }
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

  /**
   * DAILY·결정 호라이즌의 변형별 요약.
   */
  public List<VariantSummary> variantSummaries(LocalDate from, LocalDate to) {
    return variantSummaries(from, to, AdviceKind.DAILY, properties.getHorizonDays());
  }

  /**
   * 종류·호라이즌의 변형별 요약 (예: H20·20, DAILY·20 진단).
   */
  public List<VariantSummary> variantSummaries(LocalDate from, LocalDate to, AdviceKind kind, int horizonDays) {
    List<VariantSummary> result = new ArrayList<>();
    for (AdviceVariant variant : AdviceVariant.values()) {
      result.add(variantSummary(variant, from, to, kind, horizonDays));
    }
    return result;
  }

  /**
   * DAILY·결정 호라이즌의 변형 요약.
   */
  public VariantSummary variantSummary(AdviceVariant variant, LocalDate from, LocalDate to) {
    return variantSummary(variant, from, to, AdviceKind.DAILY, properties.getHorizonDays());
  }

  /**
   * 변형별 픽 KPI: 승률(초과수익>0), 평균 초과수익 ± se, 비용 차감 평균, 후보군 평균, 부가가치(픽 − 후보군). kind·h 의 채점 행만 센다.
   */
  public VariantSummary variantSummary(AdviceVariant variant, LocalDate from, LocalDate to, AdviceKind kind, int horizonDays) {
    Map<String, Object> p = params(from, to, kind, horizonDays);
    p.put("variant", variant.getCode());
    Map<String, Object> pick = jdbc.queryForMap("""
        SELECT COUNT(DISTINCT a.advice_id) AS advices, COUNT(*) AS n,
               AVG(CASE WHEN s.excess_ret > 0 THEN 1.0 ELSE 0.0 END) AS hit_rate,
               AVG(s.excess_ret) AS mean_excess, STDDEV_SAMP(s.excess_ret) AS sd_excess, AVG(s.cost_adj_excess) AS mean_cost_adj
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.advice_kind = :kind AND a.variant = :variant AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        """, p);
    Map<String, Object> avoid = jdbc.queryForMap("""
        SELECT COUNT(*) AS n, AVG(s.excess_ret) AS mean_excess
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.advice_kind = :kind AND a.variant = :variant AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND pk.direction = 'AVOID' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        """, p);
    Map<String, Object> pool = jdbc.queryForMap("""
        SELECT AVG(s.excess_ret) AS mean_excess
        FROM tb_advisor_candidate_score s
                 JOIN tb_advisor_advice a ON a.advice_id = s.advice_id
        WHERE a.advice_kind = :kind AND a.variant = :variant AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND s.horizon_days = :h AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        """, p);
    int n = ((Number) pick.get("n")).intValue();
    Double mean = d(pick.get("mean_excess"));
    Double sd = d(pick.get("sd_excess"));
    Double se = mean == null || sd == null || n < 2 ? null : sd / Math.sqrt(n);
    Double poolMean = d(pool.get("mean_excess"));
    return new VariantSummary(variant, ((Number) pick.get("advices")).intValue(), n, d(pick.get("hit_rate")), mean, se, d(pick.get("mean_cost_adj")),
        poolMean, mean == null || poolMean == null ? null : mean - poolMean, d(avoid.get("mean_excess")), ((Number) avoid.get("n")).intValue(),
        verdictLabel(kind));
  }

  /**
   * 종류의 판정 라벨: 결정 호라이즌이 advisor.horizons 에서 learn=false(모니터링 전용 — 기본 H60·H180)면 {@link #UNJUDGEABLE_LABEL}, 아니면 null.
   * 설정 맵 기반이라 호라이즌을 학습 대상으로 바꾸면 라벨도 함께 사라진다.
   */
  public String verdictLabel(AdviceKind kind) {
    java.util.OptionalInt h = properties.horizonOf(kind);
    return h.isPresent() && !properties.isLearnHorizon(h.getAsInt()) ? UNJUDGEABLE_LABEL : null;
  }

  /**
   * DAILY·결정 호라이즌의 국면 콜 요약.
   */
  public RegimeSummary regimeSummary(AdviceVariant variant, LocalDate from, LocalDate to) {
    return regimeSummary(variant, from, to, AdviceKind.DAILY, properties.getHorizonDays());
  }

  /**
   * 국면(지수 방향) 콜 요약: 적중률·평균 Brier·Brier skill = 1 − Brier/0.25. kind·h 의 채점 행만 센다.
   */
  public RegimeSummary regimeSummary(AdviceVariant variant, LocalDate from, LocalDate to, AdviceKind kind, int horizonDays) {
    Map<String, Object> p = params(from, to, kind, horizonDays);
    p.put("variant", variant.getCode());
    Map<String, Object> row = jdbc.queryForMap("""
        SELECT COUNT(*) AS n, AVG(CASE WHEN c.hit THEN 1.0 ELSE 0.0 END) AS hit_rate, AVG(c.brier) AS brier
        FROM tb_advisor_call_score c JOIN tb_advisor_advice a ON a.advice_id = c.advice_id
        WHERE a.advice_kind = :kind AND a.variant = :variant AND a.base_date BETWEEN :from AND :to AND c.subject_type = 'INDEX' AND c.horizon_days = :h AND c.status = 'SCORED'
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
        WHERE a.advice_kind = 'DAILY' AND a.variant = :variant AND a.base_date BETWEEN :from AND :to AND c.subject_type IN ('TREND', 'TREND_INV')
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
        WHERE a.advice_kind = 'DAILY' AND a.variant = :variant AND a.base_date BETWEEN :from AND :to AND c.subject_type = 'MORNING' AND c.horizon_days = 1 AND c.status = 'SCORED'
        """, p);
    return new MorningSummary(((Number) row.get("n")).intValue(), d(row.get("hit_rate")), d(row.get("caution_rate")));
  }

  /** 같은 기준일 아침 재판정·저녁 판단 1쌍: LONG 픽 평균 초과수익(결정 호라이즌)과 그날이 트리거일이었는지 (diff_json.triggers.any) */
  public record MorningPair(LocalDate baseDate, double dailyMean, double morningMean, int dailyPicks, int morningPicks, boolean triggered) {

    public double diff() {
      return morningMean - dailyMean;
    }
  }

  /** 대응 차이(MORNING − DAILY) 요약: n = 기준일 수, t = mean / se (n < 2 면 se·t 는 null) */
  public record PairedDiff(int n, Double meanDiff, Double seDiff, Double t) {
  }

  /**
   * 아침 재판정 대응 비교 KPI (M4): 전체·트리거일·비트리거일. 사전 등록 판정(40거래일 뒤 트리거일 t &gt; 2 면 유지)의 근거다.
   */
  public record MorningVsDaily(LocalDate from, LocalDate to, int horizonDays, PairedDiff all, PairedDiff triggered, PairedDiff untriggered,
                               List<MorningPair> pairs) {
  }

  /**
   * MORNING − DAILY 대응 비교. 같은 기준일에서 아침 판단(parent_advice_id 로 묶인 저녁 판단)과 저녁 판단의 LONG 픽 평균 초과수익 차이를 날짜 단위로 구하고,
   * 날짜들의 평균·se(표본 표준편차/√n)·t 를 낸다. 두 판단이 같은 창·같은 후보군이라 후보군 평균이 소거되어 차이가 곧 밤사이 정보의 가치다.
   * <p>
   * 짝의 단위가 픽이 아니라 날짜인 이유: 같은 날 픽들은 같은 시장 충격을 공유해 독립이 아니다 — 픽 단위 se 는 과소추정된다.
   * 어느 한쪽에 채점된 LONG 픽이 없는 날(아침이 LONG 을 전부 뺐거나 미채점)은 짝이 성립하지 않아 빠진다. data_quality=OK 인 저녁만 센다.
   */
  public MorningVsDaily morningVsDaily(LocalDate from, LocalDate to) {
    List<MorningPair> pairs = jdbc.query("""
        WITH pick AS (
            SELECT pk.advice_id, AVG(s.excess_ret) AS mean_excess, COUNT(*) AS n
            FROM tb_advisor_pick pk
                     JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                     JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
            WHERE a.advice_kind IN ('DAILY', 'MORNING') AND a.variant = 'LIVE' AND a.base_date BETWEEN :from AND :to
              AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
            GROUP BY pk.advice_id
        )
        SELECT m.base_date, pd.mean_excess AS daily_mean, pm.mean_excess AS morning_mean, pd.n AS daily_n, pm.n AS morning_n,
               COALESCE((m.diff_json -> 'triggers' ->> 'any')::boolean, FALSE) AS triggered
        FROM tb_advisor_advice m
                 JOIN tb_advisor_advice d ON d.advice_id = m.parent_advice_id
                 JOIN pick pm ON pm.advice_id = m.advice_id
                 JOIN pick pd ON pd.advice_id = d.advice_id
        WHERE m.advice_kind = 'MORNING' AND m.variant = 'LIVE' AND d.advice_kind = 'DAILY' AND d.variant = 'LIVE'
          AND m.base_date BETWEEN :from AND :to AND d.data_quality = 'OK'
        ORDER BY m.base_date
        """, params(from, to), (rs, i) -> new MorningPair(rs.getObject("base_date", LocalDate.class), rs.getDouble("daily_mean"), rs.getDouble("morning_mean"),
        rs.getInt("daily_n"), rs.getInt("morning_n"), rs.getBoolean("triggered")));
    return new MorningVsDaily(from, to, properties.getHorizonDays(), paired(pairs.stream().map(MorningPair::diff).toList()),
        paired(pairs.stream().filter(MorningPair::triggered).map(MorningPair::diff).toList()),
        paired(pairs.stream().filter(p -> !p.triggered()).map(MorningPair::diff).toList()), pairs);
  }

  /**
   * 차이 목록의 평균·se·t. 비면 전부 null, 1개면 평균만.
   */
  static PairedDiff paired(List<Double> diffs) {
    int n = diffs.size();
    if (n == 0) {
      return new PairedDiff(0, null, null, null);
    }
    double mean = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    if (n < 2) {
      return new PairedDiff(n, mean, null, null);
    }
    double ss = diffs.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum();
    double se = Math.sqrt(ss / (n - 1)) / Math.sqrt(n);
    return new PairedDiff(n, mean, se, se > 0 ? mean / se : null);
  }

  /**
   * DAILY·결정 호라이즌 보정 표 — 교훈(WEEKLY_REVIEW)·프롬프트 실적 블록의 입력.
   */
  public List<CalibrationRow> calibration(LocalDate from, LocalDate to) {
    return calibration(from, to, AdviceKind.DAILY, properties.getHorizonDays());
  }

  /**
   * 신뢰도 버킷별 보정 표 (LIVE, LONG, kind·h).
   */
  public List<CalibrationRow> calibration(LocalDate from, LocalDate to, AdviceKind kind, int horizonDays) {
    Map<String, Object> p = params(from, to, kind, horizonDays);
    return jdbc.query("""
        SELECT pk.conviction, COUNT(*) AS n, AVG(CASE WHEN s.excess_ret > 0 THEN 1.0 ELSE 0.0 END) AS hit_rate, AVG(s.excess_ret) AS mean_excess
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.advice_kind = :kind AND a.variant = 'LIVE' AND a.base_date BETWEEN :from AND :to AND a.data_quality = 'OK'
          AND pk.direction = 'LONG' AND s.status <> 'MISSING' AND s.excess_ret IS NOT NULL
        GROUP BY pk.conviction ORDER BY pk.conviction
        """, p, (rs, i) -> new CalibrationRow(rs.getDouble("conviction"), rs.getInt("n"), d(rs.getObject("hit_rate")), d(rs.getObject("mean_excess"))));
  }

  /**
   * DAILY·결정 호라이즌의 최근 채점 LIVE 픽.
   */
  public List<RecentPick> recentPicks(LocalDate from, LocalDate to, int limit) {
    return recentPicks(from, to, limit, AdviceKind.DAILY, properties.getHorizonDays());
  }

  /**
   * 최근 채점된 LIVE 픽 (최신순, kind·h).
   */
  public List<RecentPick> recentPicks(LocalDate from, LocalDate to, int limit, AdviceKind kind, int horizonDays) {
    Map<String, Object> p = params(from, to, kind, horizonDays);
    p.put("limit", limit);
    return jdbc.query("""
        SELECT a.base_date, pk.ticker, c.stock_name, pk.conviction, s.excess_ret
        FROM tb_advisor_pick pk
                 JOIN tb_advisor_advice a ON a.advice_id = pk.advice_id
                 JOIN tb_advisor_candidate c ON c.advice_id = pk.advice_id AND c.ticker = pk.ticker
                 JOIN tb_advisor_candidate_score s ON s.advice_id = pk.advice_id AND s.ticker = pk.ticker AND s.horizon_days = :h
        WHERE a.advice_kind = :kind AND a.variant = 'LIVE' AND a.base_date BETWEEN :from AND :to AND pk.direction = 'LONG' AND s.status <> 'MISSING'
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

  /**
   * DAILY·결정 호라이즌 파라미터 (추세·아침 점검·대응 비교처럼 DAILY 전용 집계).
   */
  private Map<String, Object> params(LocalDate from, LocalDate to) {
    return params(from, to, AdviceKind.DAILY, properties.getHorizonDays());
  }

  private Map<String, Object> params(LocalDate from, LocalDate to, AdviceKind kind, int horizonDays) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("from", from);
    p.put("to", to);
    p.put("h", horizonDays);
    p.put("kind", kind.getCode());
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

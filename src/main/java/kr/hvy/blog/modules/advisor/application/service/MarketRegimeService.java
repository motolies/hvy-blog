package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 합성 국면(M6): 규칙 추세(MarketTrendService 가 이미 계산한 값) × 변동성 국면 → 정책 표 한도 + 테마 강약. 결과는 프롬프트 regime·theme 블록과 regime_json 의 원천이다.
 * <p>
 * 변동성 국면: 국면 기준 지수의 σ_w(직전 w 거래일 ret_1d 표본 표준편차, 창이 꽉 찬 날만)를 기준일 이전 최대 lookback 년 σ_w 분포의 백분위로 나눈다.
 * 룩어헤드 없음 — CTE 전체가 trade_date ≤ :d 로 잘리고, 백분위 분포는 기준일(최신 행) 앞의 날짜만 센다. 백분위 = (분포 중 오늘 σ 보다 작은 값의 수) / (분포 크기).
 */
@Service
public class MarketRegimeService {

  /** σ 창 폭은 설정 정수라 SQL 에 리터럴로 넣는다(창 프레임 오프셋). lookback 앞에 창 폭만큼 여유(캘린더 60일)를 둬 첫 σ 도 꽉 찬 창이 되게 한다 */
  static final String VOL_SQL = """
      WITH s AS (
          SELECT trade_date,
                 STDDEV_SAMP(ret_1d) OVER w AS sigma,
                 COUNT(ret_1d) OVER w       AS n
          FROM mv_stock_index_metric
          WHERE index_code = :code AND trade_date <= :d
            AND trade_date > CAST(:d AS date) - (:years * INTERVAL '1 year') - INTERVAL '60 days'
          WINDOW w AS (ORDER BY trade_date ROWS BETWEEN %d PRECEDING AND CURRENT ROW)
      ),
      v AS (SELECT trade_date, sigma FROM s WHERE n = :win AND trade_date > CAST(:d AS date) - (:years * INTERVAL '1 year')),
      cur AS (SELECT trade_date, sigma FROM v ORDER BY trade_date DESC LIMIT 1)
      SELECT cur.trade_date, cur.sigma,
             (SELECT COUNT(*) FROM v WHERE v.trade_date < cur.trade_date)                      AS hist,
             (SELECT COUNT(*) FROM v WHERE v.trade_date < cur.trade_date AND v.sigma < cur.sigma) AS below
      FROM cur
      """;

  /** σ 한 줄: 기준일 σ, 분포 표본 수, 백분위(표본 0 이면 null) */
  record VolReading(LocalDate tradeDate, Double sigma, int history, Double pct) {
  }

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;
  private final ThemeStrengthService themeService;
  private final RegimePolicy policy;

  public MarketRegimeService(NamedParameterJdbcTemplate jdbc, AdvisorProperties properties, ThemeStrengthService themeService) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.themeService = themeService;
    this.policy = new RegimePolicy(properties);
  }

  /**
   * 기준일 합성 국면. trends 는 같은 기준일의 규칙 추세(MarketFeatureService 가 이미 조회) — 국면 기준 지수의 라벨이 없으면 trend·policy 는 null.
   */
  public MarketRegime regime(LocalDate asOf, List<MarketTrend> trends) {
    AdvisorProperties.Regime cfg = properties.getRegime();
    MarketTrend trend = trends == null ? null : trends.stream().filter(t -> cfg.getIndexCode().equals(t.indexCode())).findFirst().orElse(null);
    VolReading reading = vol(asOf);
    VolRegimeCode vol = classify(reading, cfg);
    return MarketRegime.builder()
        .indexCode(cfg.getIndexCode())
        .tradeDate(trend == null ? reading.tradeDate() : trend.tradeDate())
        .trend(trend == null ? null : trend.code())
        .trendScore(trend == null ? null : trend.score())
        .vol(vol)
        .volPct(reading.pct())
        .sigma20(reading.sigma())
        .volHistoryDays(reading.history())
        .policy(policy.limits(trend == null ? null : trend.code(), vol))
        .themes(themeService.themes(asOf))
        .build();
  }

  /**
   * 기준일 σ 와 과거 분포 백분위. 지수 행이 없으면 전부 비어 있는 읽기값.
   */
  VolReading vol(LocalDate asOf) {
    AdvisorProperties.Regime cfg = properties.getRegime();
    int window = cfg.getVolWindowDays();
    List<VolReading> rows = jdbc.query(String.format(VOL_SQL, window - 1),
        Map.of("code", cfg.getIndexCode(), "d", asOf, "years", cfg.getVolLookbackYears(), "win", window),
        (rs, i) -> {
          int hist = rs.getInt("hist");
          int below = rs.getInt("below");
          return new VolReading(rs.getObject("trade_date", LocalDate.class), rs.getDouble("sigma"), hist, hist == 0 ? null : (double) below / hist);
        });
    return rows.isEmpty() ? new VolReading(null, null, 0, null) : rows.getFirst();
  }

  /**
   * 백분위 → 변동성 국면. 분포 표본이 vol-min-history-days 미만이면 UNKNOWN(표본이 짧은 분포의 백분위는 국면이라 부를 수 없다).
   */
  static VolRegimeCode classify(VolReading reading, AdvisorProperties.Regime cfg) {
    if (reading.pct() == null || reading.history() < cfg.getVolMinHistoryDays()) {
      return VolRegimeCode.UNKNOWN;
    }
    if (reading.pct() >= cfg.getVolHighPct()) {
      return VolRegimeCode.HIGH;
    }
    return reading.pct() < cfg.getVolLowPct() ? VolRegimeCode.LOW : VolRegimeCode.NORMAL;
  }
}

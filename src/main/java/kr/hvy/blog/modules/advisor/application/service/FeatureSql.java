package kr.hvy.blog.modules.advisor.application.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;

/**
 * 스크리닝·IC 가 공유하는 특징 SQL 조립. 룩어헤드 불변식: 모든 특징은 기준일 이하 행만 쓴다(LEAD 는 IC 의 실현 수익률 계산에만).
 * <p>
 * 파라미터는 명명 파라미터(:from, :to 등)로만 바인딩하고 시그널 표현식은 enum 상수 문자열이다.
 * 파생 테이블에 없는 20일 변동성·섹터 5일 강도는 창 함수로 즉석 계산한다(스크리닝은 하루치라 수 초, IC 백필은 월 단위 청크).
 */
public final class FeatureSql {

  private FeatureSql() {
  }

  /**
   * 기간 [:from, :to] 의 유니버스 특징 행 CTE 묶음 (feat 까지). 호출자가 뒤에 SELECT 를 붙인다.
   * cal 은 영업일(KOSPI 일봉 존재일), base 는 유니버스, vol/sector5 는 창 함수 보조.
   * <p>
   * feat 는 {@code :markets}(advisor.markets, 시장 코드 목록) 로 시장을 한정한다 — 호출자는 :from/:to 와 함께 반드시 바인딩한다.
   * 스크리닝 백분위·유니버스 수·rank-IC 표본이 모두 이 필터 뒤의 행이므로 세 척도가 같은 유니버스를 쓴다(advice-v5, 2026-09-13).
   */
  public static String featureCtes() {
    return """
        WITH cal AS (
            SELECT trade_date FROM vw_stock_market_calendar WHERE trade_date BETWEEN :from AND :to
        ),
        base AS (
            SELECT u.trade_date, u.ticker
            FROM vw_stock_universe_daily u
                     JOIN cal ON cal.trade_date = u.trade_date
        ),
        vol AS (
            SELECT ticker, trade_date,
                   STDDEV_SAMP(ret_1d) OVER (PARTITION BY ticker ORDER BY trade_date ROWS BETWEEN 19 PRECEDING AND CURRENT ROW) AS vol_20d,
                   COUNT(ret_1d) OVER (PARTITION BY ticker ORDER BY trade_date ROWS BETWEEN 19 PRECEDING AND CURRENT ROW)       AS vol_n
            FROM tb_stock_daily_metric
            WHERE trade_date BETWEEN (CAST(:from AS date) - INTERVAL '45 days') AND :to
        ),
        sector5 AS (
            -- 동일가중 등락 합: 시총가중은 밸류 스냅샷(당일만)에 의존해 과거 IC 를 구할 수 없으므로 라이브·IC 모두 동일가중으로 통일한다
            SELECT sector_code, trade_date,
                   SUM(avg_change_rate) OVER (PARTITION BY sector_code ORDER BY trade_date ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS cw_5d
            FROM mv_stock_sector_daily
            WHERE trade_date BETWEEN (CAST(:from AS date) - INTERVAL '15 days') AND :to
        ),
        feat AS (
            SELECT m.ticker, m.trade_date, ms.stock_name, ms.market_type,
                   CASE ms.market_type WHEN 'KOSPI' THEN '0001' ELSE '1001' END AS bench_index_code,
                   m.adj_close, m.ret_1d, m.ret_5d, m.ret_20d, m.ret_60d, m.dist_ma20, m.dist_ma60, m.dist_high_52w,
                   m.tv_ratio_5_60, m.tv_avg_5d, m.tv_avg_60d, m.ma_5, m.ma_20, m.ma_60, m.ma_120,
                   m.foreign_net_5d, m.institution_net_5d,
                   v.market_cap, v.per, v.pbr, v.foreign_hold_rate,
                   sm.sector_code, sm.sector_name, sc.cw_5d AS sector_cw_5d,
                   CASE WHEN vo.vol_n >= 15 THEN vo.vol_20d END AS vol_20d,
                   ix.ret_20d AS index_ret_20d,
                   p.close_price AS raw_close
            FROM base b
                     JOIN tb_stock_daily_metric m ON m.ticker = b.ticker AND m.trade_date = b.trade_date
                     JOIN tb_stock_master ms ON ms.ticker = m.ticker
                     JOIN tb_stock_daily_price p ON p.ticker = m.ticker AND p.trade_date = m.trade_date
                     LEFT JOIN tb_stock_valuation_daily v ON v.ticker = m.ticker AND v.trade_date = m.trade_date
                     LEFT JOIN tb_stock_sector_map sm ON sm.ticker = m.ticker AND sm.source = 'KRX' AND sm.valid_to IS NULL
                     LEFT JOIN sector5 sc ON sc.sector_code = sm.sector_code AND sc.trade_date = m.trade_date
                     LEFT JOIN vol vo ON vo.ticker = m.ticker AND vo.trade_date = m.trade_date
                     LEFT JOIN mv_stock_index_metric ix ON ix.index_code = CASE ms.market_type WHEN 'KOSPI' THEN '0001' ELSE '1001' END
                                                       AND ix.trade_date = m.trade_date
            WHERE ms.market_type IN (:markets)
        )
        """;
  }

  /**
   * 시그널별 백분위 컬럼 절: PERCENT_RANK() OVER (PARTITION BY trade_date, expr IS NULL ORDER BY expr) AS p_CODE.
   * NULL 행을 같은 창에 두면 비NULL 행의 백분위가 [0, 1] 을 못 채우므로 NULL 여부로 창을 나누고, NULL 행의 값은 CASE 로 NULL 로 돌려
   * 점수 식에서 0.5(기여 0)로 대체한다.
   */
  public static String percentRankColumns(List<SignalCode> signals) {
    return signals.stream()
        .map(s -> "CASE WHEN (" + s.getExpression() + ") IS NULL THEN NULL ELSE PERCENT_RANK() OVER (PARTITION BY f.trade_date, "
            + "((" + s.getExpression() + ") IS NULL) ORDER BY (" + s.getExpression() + ")) END AS p_" + s.getCode())
        .collect(Collectors.joining(",\n       "));
  }

  /**
   * 원값 컬럼 절: expr AS v_CODE (스냅샷 저장용).
   */
  public static String rawValueColumns(List<SignalCode> signals) {
    return signals.stream()
        .map(s -> "(" + s.getExpression() + ") AS v_" + s.getCode())
        .collect(Collectors.joining(",\n       "));
  }

  /**
   * 종합 점수 식: Σ w·s / Σ w, s = ±(2·pct − 1), NULL pct → 기여 0. 가중치는 :w_CODE 로 바인딩한다.
   */
  public static String scoreExpression(List<SignalCode> signals) {
    String numerator = signals.stream()
        .map(s -> ":w_" + s.getCode() + " * (" + (s.isHigherIsBetter() ? "" : "-") + "(2 * COALESCE(p_" + s.getCode() + ", 0.5) - 1))")
        .collect(Collectors.joining("\n         + "));
    return "(" + numerator + ") / :w_sum";
  }

  /**
   * 가중치 파라미터 맵 (:w_CODE, :w_sum).
   */
  public static Map<String, Object> weightParams(Map<String, Double> weights, List<SignalCode> signals) {
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    double sum = 0;
    for (SignalCode s : signals) {
      double w = weights.getOrDefault(s.getCode(), 0.0);
      params.put("w_" + s.getCode(), w);
      sum += w;
    }
    params.put("w_sum", sum == 0 ? 1.0 : sum);
    return params;
  }

  /**
   * IC 계산용 언피벗 VALUES: 학습 대상 시그널마다 (code, 부호 맞춘 값). 낮을수록 좋은 시그널은 부호를 뒤집어 "IC 양수 = 유효" 로 통일한다.
   */
  public static String icValuesList(List<SignalCode> signals) {
    return signals.stream()
        .map(s -> "('" + s.getCode() + "', " + (s.isHigherIsBetter() ? "" : "-") + "(" + s.getExpression() + "))")
        .collect(Collectors.joining(",\n              "));
  }
}

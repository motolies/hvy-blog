package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;

/**
 * 규칙 기반 중기 추세 라벨의 단일 SQL 정의. {@link MarketTrendService}(기준일 조회·기저율)와 {@link AdviceScoringService}(20일 창 재현)가
 * 같은 CTE 를 써야 계산과 채점의 정의가 갈라지지 않는다(FeatureSql 이 스크리닝과 IC 에 같은 표현식을 공급하는 것과 같은 구조).
 * <p>
 * 성분 5개 각 -1/0/+1 (NULL 은 0) → 합(score) → raw 라벨(임계) → confirm-days 연속 같은 raw 일 때만 확정 라벨 갱신(그 전엔 직전 확정값 유지)
 * → 확정 라벨 에피소드(since·days). 확정은 LAG 만 보는 인과 계산이라 상한(:to)을 어디에 두든 그 이전 날짜의 라벨은 같다(룩어헤드 없음).
 * PostgreSQL 에 IGNORE NULLS 가 없어 "마지막 비 NULL 끌어오기" 는 누적 COUNT 파티션 + FIRST_VALUE 관용구로 쓰고, 창 함수는 중첩할 수 없어
 * LAG 와 그 위의 SUM 을 CTE 두 단으로 나눈다.
 */
public final class TrendSql {

  /** 시장 breadth MV 의 market_type ↔ 지수 코드 */
  static final String BREADTH_CTE = """
      bre AS (
          SELECT CASE market_type WHEN 'KOSPI' THEN '0001' WHEN 'KOSDAQ' THEN '1001' END AS index_code, trade_date, above_ma20_ratio
          FROM mv_stock_market_breadth_daily WHERE market_type IN ('KOSPI', 'KOSDAQ')
      )""";

  private TrendSql() {
  }

  /**
   * 확정 라벨 CTE 체인. 마지막 CTE 이름은 {@code lbl} 이며 컬럼은
   * index_code, trade_date, close_value, ma_20, ma_60, ma_120, ret_60d, breadth, c_ma20, c_ma60, c_ma120, c_ret60, c_breadth, score,
   * raw_code, trend_code, since, days.
   * 파라미터: :codes, :to, :ret60, :breadthHigh, :breadthLow, :bull, :bear, :confirm ({@link #params}).
   * 호출자는 {@code "WITH " + labelCtes() + ", 다음 CTE ..."} 로 이어 붙인다.
   */
  public static String labelCtes() {
    return BREADTH_CTE + """
        ,
        comp AS (
            SELECT i.index_code, i.trade_date, i.close_value, i.ma_20, i.ma_60, i.ma_120, i.ret_60d, b.above_ma20_ratio AS breadth,
                   CASE WHEN i.ma_20 IS NULL THEN 0 WHEN i.close_value > i.ma_20 THEN 1 WHEN i.close_value < i.ma_20 THEN -1 ELSE 0 END AS c_ma20,
                   CASE WHEN i.ma_20 IS NULL OR i.ma_60 IS NULL THEN 0 WHEN i.ma_20 > i.ma_60 THEN 1 WHEN i.ma_20 < i.ma_60 THEN -1 ELSE 0 END AS c_ma60,
                   CASE WHEN i.ma_60 IS NULL OR i.ma_120 IS NULL THEN 0 WHEN i.ma_60 > i.ma_120 THEN 1 WHEN i.ma_60 < i.ma_120 THEN -1 ELSE 0 END AS c_ma120,
                   CASE WHEN i.ret_60d IS NULL THEN 0 WHEN i.ret_60d >= :ret60 THEN 1 WHEN i.ret_60d <= -(:ret60) THEN -1 ELSE 0 END AS c_ret60,
                   CASE WHEN b.above_ma20_ratio IS NULL THEN 0 WHEN b.above_ma20_ratio >= :breadthHigh THEN 1
                        WHEN b.above_ma20_ratio <= :breadthLow THEN -1 ELSE 0 END AS c_breadth
            FROM mv_stock_index_metric i
                     LEFT JOIN bre b ON b.index_code = i.index_code AND b.trade_date = i.trade_date
            WHERE i.index_code IN (:codes) AND i.trade_date <= :to
        ),
        raw AS (
            SELECT c.*, c.c_ma20 + c.c_ma60 + c.c_ma120 + c.c_ret60 + c.c_breadth AS score,
                   CASE WHEN c.c_ma20 + c.c_ma60 + c.c_ma120 + c.c_ret60 + c.c_breadth >= :bull THEN 'BULL'
                        WHEN c.c_ma20 + c.c_ma60 + c.c_ma120 + c.c_ret60 + c.c_breadth <= :bear THEN 'BEAR'
                        ELSE 'SIDEWAYS' END AS raw_code
            FROM comp c
        ),
        lagged AS (
            SELECT r.*, LAG(r.raw_code) OVER (PARTITION BY r.index_code ORDER BY r.trade_date) AS prev_raw
            FROM raw r
        ),
        run AS (
            SELECT l.*, SUM(CASE WHEN l.raw_code = l.prev_raw THEN 0 ELSE 1 END) OVER (PARTITION BY l.index_code ORDER BY l.trade_date) AS run_id
            FROM lagged l
        ),
        confirmable AS (
            SELECT r.*, CASE WHEN ROW_NUMBER() OVER (PARTITION BY r.index_code, r.run_id ORDER BY r.trade_date) >= :confirm THEN r.raw_code END AS c
            FROM run r
        ),
        grp AS (
            SELECT c.*, COUNT(c.c) OVER (PARTITION BY c.index_code ORDER BY c.trade_date) AS g
            FROM confirmable c
        ),
        confirmed AS (
            SELECT g.*, COALESCE(FIRST_VALUE(g.c) OVER (PARTITION BY g.index_code, g.g ORDER BY g.trade_date), g.raw_code) AS trend_code
            FROM grp g
        ),
        elag AS (
            SELECT c.*, LAG(c.trend_code) OVER (PARTITION BY c.index_code ORDER BY c.trade_date) AS prev_trend
            FROM confirmed c
        ),
        episode AS (
            SELECT e.*, SUM(CASE WHEN e.trend_code = e.prev_trend THEN 0 ELSE 1 END) OVER (PARTITION BY e.index_code ORDER BY e.trade_date) AS episode_id
            FROM elag e
        ),
        lbl AS (
            SELECT e.index_code, e.trade_date, e.close_value, e.ma_20, e.ma_60, e.ma_120, e.ret_60d, e.breadth,
                   e.c_ma20, e.c_ma60, e.c_ma120, e.c_ret60, e.c_breadth, e.score, e.raw_code, e.trend_code,
                   MIN(e.trade_date) OVER (PARTITION BY e.index_code, e.episode_id) AS since,
                   ROW_NUMBER() OVER (PARTITION BY e.index_code, e.episode_id ORDER BY e.trade_date) AS days
            FROM episode e
        )""";
  }

  /**
   * labelCtes 의 파라미터 묶음. 호출자가 자기 파라미터를 더한다.
   */
  public static Map<String, Object> params(AdvisorProperties.Trend trend, List<String> codes, LocalDate to) {
    Map<String, Object> p = new HashMap<>();
    p.put("codes", codes);
    p.put("to", to);
    p.put("ret60", trend.getRet60Threshold());
    p.put("breadthHigh", trend.getBreadthHigh());
    p.put("breadthLow", trend.getBreadthLow());
    p.put("bull", trend.getBullThreshold());
    p.put("bear", trend.getBearThreshold());
    p.put("confirm", trend.getConfirmDays());
    return p;
  }
}

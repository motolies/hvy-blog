package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 미국 연동(advice-v3): 국내 지수 ↔ 미국 심볼의 β·상관, 밤사이 미국 수익률, 지수 σ_1d.
 * <p>
 * 정렬이 룩어헤드의 핵심이다. 미국 trade_date 는 현지 날짜라 국내 d일 개장 전에 끝난 세션은 <b>d 보다 작은 현지일</b>이고, 국내 d일 수익률이
 * 반응하는 세션은 국내 직전 거래일 d_prev 의 마감 뒤에 끝난 것, 즉 현지일 ∈ [d_prev, d−1] 이다(국내 월요일 ↔ 미국 금요일). 그 구간에 미국 세션이
 * 없으면(미국 휴장) 짝을 짓지 않는다 — 이미 다른 국내일에 쓴 미국 수익률을 재사용하지 않기 위해서다. β 는 REGR_SLOPE(kr, us), corr 는 CORR.
 */
@Service
@RequiredArgsConstructor
public class GlobalLinkService {

  /** 국내 직전 거래일을 모를 때(창의 첫 행) 짝지을 미국 세션의 최대 거리(캘린더일) — 주말 3일 허용 */
  static final int MAX_PAIR_GAP_DAYS = 4;

  private static final String LINK_SQL = """
      WITH kr AS (
          SELECT trade_date, ret_1d, LAG(trade_date) OVER (ORDER BY trade_date) AS prev_date
          FROM mv_stock_index_metric
          WHERE index_code = :kr AND trade_date <= :d AND ret_1d IS NOT NULL
          ORDER BY trade_date DESC LIMIT :n
      ),
      us AS (
          SELECT trade_date, close_price / NULLIF(LAG(close_price) OVER (ORDER BY trade_date), 0) - 1 AS r1
          FROM tb_stock_global_market_daily
          WHERE symbol = :us AND trade_date < :d AND trade_date > CAST(:d AS date) - INTERVAL '400 days'
      ),
      pair AS (
          SELECT k.trade_date, k.ret_1d AS kr_r,
                 (SELECT u.r1 FROM us u
                  WHERE u.trade_date < k.trade_date AND u.trade_date >= COALESCE(k.prev_date, k.trade_date - :gap * INTERVAL '1 day')
                  ORDER BY u.trade_date DESC LIMIT 1) AS us_r
          FROM kr k
      )
      SELECT REGR_SLOPE(kr_r, us_r) AS beta, CORR(kr_r, us_r) AS corr, COUNT(us_r) AS n FROM pair
      """;

  private static final String OVERNIGHT_SQL = """
      SELECT symbol, trade_date, close_price / NULLIF(LAG(close_price) OVER (PARTITION BY symbol ORDER BY trade_date), 0) - 1 AS r1,
             ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn
      FROM tb_stock_global_market_daily
      WHERE symbol IN (:symbols) AND trade_date < :d AND trade_date > CAST(:d AS date) - INTERVAL '30 days'
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;

  /** 밤사이 미국 세션 1건 */
  public record Overnight(String symbol, LocalDate date, Double r1) {
  }

  /**
   * 설정된 쌍 전부의 연동 강도 (asOf 이하 국내 거래일 N개, 미국은 asOf 미만 현지일만).
   */
  public List<GlobalLink> links(LocalDate asOf) {
    List<GlobalLink> result = new ArrayList<>();
    for (String pair : properties.getMorning().getLinkPairs()) {
      String[] parts = pair.split(":", 2);
      if (parts.length != 2) {
        continue;
      }
      result.add(link(parts[0].trim(), parts[1].trim(), asOf));
    }
    return result;
  }

  /**
   * 쌍 1개의 β·상관·표본 수.
   */
  public GlobalLink link(String krIndex, String usSymbol, LocalDate asOf) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("kr", krIndex);
    p.put("us", usSymbol);
    p.put("d", asOf);
    p.put("n", properties.getMorning().getLinkWindowDays());
    p.put("gap", MAX_PAIR_GAP_DAYS);
    return jdbc.query(LINK_SQL, p, (rs, i) -> new GlobalLink(krIndex, usSymbol, d(rs.getObject("beta")), d(rs.getObject("corr")), rs.getInt("n")))
        .stream().findFirst().orElse(new GlobalLink(krIndex, usSymbol, null, null, 0));
  }

  /**
   * 심볼별 가장 최근 미국 세션(현지일 < before)의 날짜와 1일 수익률 — 아침 점검의 "밤사이 마감".
   */
  public Map<String, Overnight> overnight(List<String> symbols, LocalDate before) {
    Map<String, Overnight> map = new LinkedHashMap<>();
    if (symbols.isEmpty()) {
      return map;
    }
    jdbc.query(OVERNIGHT_SQL, Map.of("symbols", symbols, "d", before), rs -> {
      if (rs.getInt("rn") == 1) {
        map.put(rs.getString("symbol"), new Overnight(rs.getString("symbol"), rs.getObject("trade_date", LocalDate.class), d(rs.getObject("r1"))));
      }
    });
    return map;
  }

  /**
   * 지수 σ_1d (asOf 이하 sigma-lookback-days 의 ret_1d 표준편차). 없으면 null.
   */
  public Double sigma1d(String indexCode, LocalDate asOf) {
    return jdbc.query("SELECT STDDEV_SAMP(ret_1d) AS s FROM (SELECT ret_1d FROM mv_stock_index_metric WHERE index_code = :code AND trade_date <= :d "
            + "ORDER BY trade_date DESC LIMIT :n) t",
        Map.of("code", indexCode, "d", asOf, "n", properties.getScoring().getSigmaLookbackDays()), (rs, i) -> d(rs.getObject("s")))
        .stream().findFirst().orElse(null);
  }

  private static Double d(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }
}

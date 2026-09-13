package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 기준일의 규칙 기반 중기 추세(KOSPI·KOSDAQ)와 기저율. 라벨 정의는 {@link TrendSql} 하나뿐이고 채점도 같은 CTE 를 쓴다.
 * <p>
 * 기저율은 기준일 이하 이력만으로 계산한다 — 후행 5·20일 수익률은 LEAD 로 만들지만 CTE 가 :to 로 잘려 있어 기준일을 넘는 창은 저절로 NULL 이
 * 된다(룩어헤드 없음). 에피소드 길이 중앙값은 진행 중인 현재 구간을 뺀다. 겹침 표본이라 n 은 명목값이며 프롬프트에 그렇게 설명한다.
 */
@Service
@RequiredArgsConstructor
public class MarketTrendService {

  static final List<String> INDEX_CODES = List.of("0001", "1001");

  private static final String CURRENT_SQL = "WITH " + TrendSql.labelCtes() + """
      ,
      latest AS (SELECT l.*, ROW_NUMBER() OVER (PARTITION BY l.index_code ORDER BY l.trade_date DESC) AS rn FROM lbl l)
      SELECT * FROM latest WHERE rn = 1 ORDER BY index_code
      """;

  private static final String BASE_SQL = "WITH " + TrendSql.labelCtes() + """
      ,
      mx AS (SELECT index_code, MAX(trade_date) AS md FROM lbl GROUP BY index_code),
      cur AS (SELECT l.index_code, l.trend_code FROM lbl l JOIN mx ON mx.index_code = l.index_code AND mx.md = l.trade_date),
      fwd AS (
          SELECT l.index_code, l.trend_code,
                 LEAD(l.close_value, :h5) OVER w / NULLIF(l.close_value, 0) - 1  AS f5,
                 LEAD(l.close_value, :h20) OVER w / NULLIF(l.close_value, 0) - 1 AS f20
          FROM lbl l WINDOW w AS (PARTITION BY l.index_code ORDER BY l.trade_date)
      ),
      agg AS (
          SELECT f.index_code, f.trend_code,
                 COUNT(f.f5) AS n5,  AVG(CASE WHEN f.f5 IS NULL THEN NULL WHEN f.f5 > 0 THEN 1.0 ELSE 0.0 END) AS p5,  AVG(f.f5) AS m5,
                 COUNT(f.f20) AS n20, AVG(CASE WHEN f.f20 IS NULL THEN NULL WHEN f.f20 > 0 THEN 1.0 ELSE 0.0 END) AS p20, AVG(f.f20) AS m20
          FROM fwd f GROUP BY f.index_code, f.trend_code
      ),
      ep AS (SELECT index_code, trend_code, since, COUNT(*) AS len, MAX(trade_date) AS last_date FROM lbl GROUP BY index_code, trend_code, since),
      epagg AS (
          SELECT ep.index_code, ep.trend_code, COUNT(*) AS episodes, PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY ep.len) AS median_days
          FROM ep JOIN mx ON mx.index_code = ep.index_code
          WHERE ep.last_date < mx.md
          GROUP BY ep.index_code, ep.trend_code
      )
      SELECT c.index_code, a.n5, a.p5, a.m5, a.n20, a.p20, a.m20, e.episodes, e.median_days
      FROM cur c
               LEFT JOIN agg a ON a.index_code = c.index_code AND a.trend_code = c.trend_code
               LEFT JOIN epagg e ON e.index_code = c.index_code AND e.trend_code = c.trend_code
      ORDER BY c.index_code
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;

  /**
   * 기준일(이하 최신 행)의 지수별 추세와 기저율. 지수 지표가 아직 없으면 빈 목록.
   */
  public List<MarketTrend> trends(LocalDate asOf) {
    Map<String, Object> p = TrendSql.params(properties.getTrend(), INDEX_CODES, asOf);
    List<MarketTrend> current = jdbc.query(CURRENT_SQL, p, (rs, i) -> {
      Map<String, Integer> components = new LinkedHashMap<>();
      components.put("ma20", rs.getInt("c_ma20"));
      components.put("ma60", rs.getInt("c_ma60"));
      components.put("ma120", rs.getInt("c_ma120"));
      components.put("ret60", rs.getInt("c_ret60"));
      components.put("breadth", rs.getInt("c_breadth"));
      return MarketTrend.builder()
          .indexCode(rs.getString("index_code"))
          .tradeDate(rs.getObject("trade_date", LocalDate.class))
          .code(MarketTrendCode.valueOf(rs.getString("trend_code")))
          .rawCode(MarketTrendCode.valueOf(rs.getString("raw_code")))
          .score(rs.getInt("score"))
          .components(components)
          .since(rs.getObject("since", LocalDate.class))
          .days(rs.getInt("days"))
          .close(d(rs.getObject("close_value")))
          .ma20(d(rs.getObject("ma_20"))).ma60(d(rs.getObject("ma_60"))).ma120(d(rs.getObject("ma_120")))
          .breadth(d(rs.getObject("breadth")))
          .build();
    });
    if (current.isEmpty()) {
      return List.of();
    }
    p.put("h5", properties.getHorizonDays());
    p.put("h20", properties.getTrend().getScoreHorizonDays());
    Map<String, MarketTrend.Base> bases = new LinkedHashMap<>();
    jdbc.query(BASE_SQL, p, rs -> {
      bases.put(rs.getString("index_code"), new MarketTrend.Base(
          rs.getInt("episodes"), d(rs.getObject("median_days")),
          new MarketTrend.Forward(rs.getInt("n5"), d(rs.getObject("p5")), d(rs.getObject("m5"))),
          new MarketTrend.Forward(rs.getInt("n20"), d(rs.getObject("p20")), d(rs.getObject("m20")))));
    });
    List<MarketTrend> result = new ArrayList<>();
    for (MarketTrend t : current) {
      result.add(t.toBuilder().base(bases.get(t.indexCode())).build());
    }
    return result;
  }

  private static Double d(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }
}

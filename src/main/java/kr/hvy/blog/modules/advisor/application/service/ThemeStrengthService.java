package kr.hvy.blog.modules.advisor.application.service;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.ThemeStrength;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 테마 강약(M6): 기준일에 KOSPI200 구성인 종목을 kospi200_sector 대분류별로 묶어 중앙값 수익률의 KOSPI 대비 초과(rs5/20/60)·20일선 위 비율·강약 라벨을 낸다.
 * <p>
 * PIT: 구성 여부는 마스터 SCD2 이력(tb_stock_master_history, [valid_from, valid_to) 반열림)의 그날 행으로 판정한다 — 편입 전 종목은 들어오지 않는다(FeatureSql 의
 * is_kospi200 과 같은 조인). 대분류 코드는 이력 테이블에 없어 현재 마스터(tb_stock_master.kospi200_sector)에서 온다 — 섹터 재분류는 드물지만 과거 날짜 재실행에서는
 * 오늘 분류로 묶이는 한계가 있고, 지금은 편출돼 현재 코드가 NULL 인 과거 구성 종목은 빠진다(운영 문서 §M6 에 기록).
 * <p>
 * 날짜는 국면 기준 지수(advisor.regime.index-code)의 기준일 이하 최신 행에 맞춘다 — 종목 지표와 지수 수익률이 같은 날이어야 rs 가 성립한다.
 */
@Service
@RequiredArgsConstructor
public class ThemeStrengthService {

  static final String THEME_SQL = """
      WITH k AS (
          SELECT trade_date, ret_5d, ret_20d, ret_60d FROM mv_stock_index_metric
          WHERE index_code = :code AND trade_date <= :d AND trade_date > CAST(:d AS date) - INTERVAL '10 days'
          ORDER BY trade_date DESC LIMIT 1
      ),
      mem AS (
          SELECT ms.kospi200_sector AS theme, ms.stock_name, m.ret_5d, m.ret_20d, m.ret_60d, m.adj_close, m.ma_20, m.tv_avg_60d
          FROM k
                   JOIN tb_stock_daily_metric m ON m.trade_date = k.trade_date
                   JOIN tb_stock_master_history mh ON mh.ticker = m.ticker AND mh.valid_from <= k.trade_date
                                                  AND (mh.valid_to IS NULL OR mh.valid_to > k.trade_date) AND mh.is_kospi200
                   JOIN tb_stock_master ms ON ms.ticker = m.ticker
          WHERE ms.kospi200_sector IS NOT NULL
      )
      SELECT mem.theme, COUNT(*) AS members,
             PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY mem.ret_5d) - MAX(k.ret_5d)   AS rs5,
             PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY mem.ret_20d) - MAX(k.ret_20d) AS rs20,
             PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY mem.ret_60d) - MAX(k.ret_60d) AS rs60,
             AVG(CASE WHEN mem.ma_20 IS NULL OR mem.adj_close IS NULL THEN NULL WHEN mem.adj_close > mem.ma_20 THEN 1.0 ELSE 0.0 END) AS breadth,
             ARRAY_AGG(mem.stock_name ORDER BY mem.tv_avg_60d DESC NULLS LAST, mem.stock_name) AS names
      FROM mem CROSS JOIN k
      GROUP BY mem.theme
      HAVING COUNT(*) >= :minMembers
      ORDER BY rs20 DESC NULLS LAST, mem.theme
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;

  /**
   * 기준일 테마 강약 (rs20 내림차순). 지수·종목 지표가 없으면 빈 목록.
   */
  public List<MarketRegime.Theme> themes(LocalDate asOf) {
    AdvisorProperties.Theme cfg = properties.getTheme();
    return jdbc.query(THEME_SQL, Map.of("d", asOf, "code", properties.getRegime().getIndexCode(), "minMembers", cfg.getMinMembers()),
        (rs, i) -> {
          Double rs5 = d(rs.getObject("rs5"));
          Double rs20 = d(rs.getObject("rs20"));
          Double rs60 = d(rs.getObject("rs60"));
          return new MarketRegime.Theme(rs.getString("theme"), rs.getInt("members"), rs5, rs20, rs60, d(rs.getObject("breadth")),
              strength(rs20, rs60, cfg.getStrongRs20()), leaders(rs.getArray("names"), cfg.getLeaders()));
        });
  }

  /**
   * 강약 라벨: rs20 ≥ +임계 이고 rs60 &gt; 0 → STRONG, rs20 ≤ −임계 이고 rs60 &lt; 0 → WEAK, 그 외(값 없음 포함) NEUTRAL.
   * 1개월 초과를 주 신호로, 3개월 초과를 방향 확인으로 쓴다 — 한 주 급등락만으로 라벨이 뒤집히지 않게.
   */
  static ThemeStrength strength(Double rs20, Double rs60, double threshold) {
    if (rs20 == null || rs60 == null) {
      return ThemeStrength.NEUTRAL;
    }
    if (rs20 >= threshold && rs60 > 0) {
      return ThemeStrength.STRONG;
    }
    if (rs20 <= -threshold && rs60 < 0) {
      return ThemeStrength.WEAK;
    }
    return ThemeStrength.NEUTRAL;
  }

  /**
   * 거래대금순 종목명 배열의 앞 n 개. 배열 슬라이스를 SQL 에 두지 않는 이유: "[1:n]" 의 콜론을 명명 파라미터 파서가 파라미터로 읽는다.
   */
  private static List<String> leaders(Array array, int n) throws SQLException {
    if (array == null || n <= 0) {
      return List.of();
    }
    Object[] values = (Object[]) array.getArray();
    List<String> names = new ArrayList<>();
    Arrays.stream(values).limit(n).forEach(v -> names.add(String.valueOf(v)));
    return names;
  }

  private static Double d(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }
}

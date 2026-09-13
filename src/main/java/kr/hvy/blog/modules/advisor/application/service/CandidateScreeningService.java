package kr.hvy.blog.modules.advisor.application.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 정량 깔때기: 유니버스(≈1,200) → 1차 컷(≈300) → 종합 점수 상위 N(섹터당 ≤k). LLM 은 이 후보만 본다.
 * <p>
 * 백분위는 1차 컷이 아니라 유니버스 전체 기준으로 매겨 IC 와 같은 척도를 쓴다. 결과 후보에는 그날의 시그널 백분위·가중치·원값을
 * 스냅샷으로 넣어(동결) 가중치가 바뀌어도 재현된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateScreeningService {

  /** 1차 컷: 20일 모멘텀 양수 또는 거래대금 급증, 그리고 60일선 위 (하락 추세 전체를 LLM 에 보낼 이유가 없다) */
  static final String FIRST_CUT = "(f.ret_20d > 0 OR f.tv_ratio_5_60 > 1.3) AND f.adj_close > f.ma_60";

  private final NamedParameterJdbcTemplate jdbc;
  private final WeightSetRepository weightSets;
  private final AdvisorProperties properties;

  /**
   * 활성 가중치 세트로 기준일 후보를 뽑는다.
   */
  public ScreeningResult screen(LocalDate baseDate) {
    WeightSet set = weightSets.active().orElseThrow(() -> new IllegalStateException("활성 가중치 세트가 없습니다 (advisor-seed.sql 적용 필요)"));
    return screen(baseDate, set, properties.getCandidateLimit(), properties.getMaxPerSector());
  }

  /**
   * 지정 세트·상한으로 후보를 뽑는다 (섀도·재현용).
   */
  public ScreeningResult screen(LocalDate baseDate, WeightSet set, int limit, int maxPerSector) {
    List<SignalCode> signals = SignalCode.scorable().stream()
        .filter(s -> set.enabledWeights().containsKey(s.getCode()))
        .toList();
    Map<String, Double> weights = set.enabledWeights();

    Map<String, Object> params = new LinkedHashMap<>(FeatureSql.weightParams(weights, signals));
    params.put("from", baseDate);
    params.put("to", baseDate);
    params.put("limit", limit);
    params.put("maxPerSector", maxPerSector);

    String sql = FeatureSql.featureCtes()
        + ", pct AS (\n"
        + "    SELECT f.*,\n       " + FeatureSql.percentRankColumns(signals) + ",\n       " + FeatureSql.rawValueColumns(signals) + "\n"
        + "    FROM feat f\n"
        + "),\n"
        + "scored AS (\n"
        + "    SELECT pct.*, " + FeatureSql.scoreExpression(signals) + " AS score,\n"
        + "           (SELECT COUNT(*) FROM feat) AS universe_size,\n"
        + "           (SELECT COUNT(*) FROM feat f WHERE " + FIRST_CUT + ") AS cut_size\n"
        + "    FROM pct\n"
        + "    WHERE " + FIRST_CUT.replace("f.", "pct.") + "\n"
        + "),\n"
        + "ranked AS (\n"
        + "    SELECT scored.*, ROW_NUMBER() OVER (PARTITION BY COALESCE(sector_code, '-') ORDER BY score DESC, ticker) AS sector_rn\n"
        + "    FROM scored\n"
        + ")\n"
        + "SELECT * FROM ranked WHERE sector_rn <= :maxPerSector ORDER BY score DESC, ticker LIMIT :limit";

    long started = System.currentTimeMillis();
    List<RowHolder> rows = jdbc.query(sql, params, (rs, i) -> read(rs, signals, weights));
    int universe = rows.isEmpty() ? countUniverse(baseDate) : rows.getFirst().universeSize;
    int cut = rows.isEmpty() ? 0 : rows.getFirst().cutSize;
    List<CandidateRow> candidates = new java.util.ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      candidates.add(rows.get(i).row.toBuilder().quantRank(i + 1).build());
    }
    log.info("스크리닝: base={}, universe={}, cut={}, candidates={}, weightSet={}, {}ms", baseDate, universe, cut, candidates.size(),
        set.weightSetId(), System.currentTimeMillis() - started);
    return new ScreeningResult(baseDate, universe, cut, set.weightSetId(), candidates);
  }

  private int countUniverse(LocalDate baseDate) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM vw_stock_universe_daily WHERE trade_date = :d", Map.of("d", baseDate), Integer.class);
    return n == null ? 0 : n;
  }

  private record RowHolder(CandidateRow row, int universeSize, int cutSize) {
  }

  private static RowHolder read(ResultSet rs, List<SignalCode> signals, Map<String, Double> weights) throws SQLException {
    Map<String, SignalValue> signalValues = new LinkedHashMap<>();
    for (SignalCode s : signals) {
      double pct = rs.getDouble("p_" + s.getCode());
      boolean pctNull = rs.wasNull();
      double raw = rs.getDouble("v_" + s.getCode());
      Double rawValue = rs.wasNull() ? null : raw;
      signalValues.put(s.getCode(), new SignalValue(pctNull ? 0.5 : round4(pct), weights.getOrDefault(s.getCode(), 0.0), rawValue == null ? null : round6(rawValue)));
    }
    Map<String, Object> features = new LinkedHashMap<>();
    putFeature(features, "close", rs, "adj_close");
    putFeature(features, "r1", rs, "ret_1d");
    putFeature(features, "r5", rs, "ret_5d");
    putFeature(features, "r20", rs, "ret_20d");
    putFeature(features, "r60", rs, "ret_60d");
    putFeature(features, "distMa20", rs, "dist_ma20");
    putFeature(features, "distMa60", rs, "dist_ma60");
    putFeature(features, "distHigh52w", rs, "dist_high_52w");
    putFeature(features, "tvRatio", rs, "tv_ratio_5_60");
    putFeature(features, "tvAvg60d", rs, "tv_avg_60d");
    putFeature(features, "frgnNet5d", rs, "foreign_net_5d");
    putFeature(features, "instNet5d", rs, "institution_net_5d");
    putFeature(features, "marketCap", rs, "market_cap");
    putFeature(features, "per", rs, "per");
    putFeature(features, "pbr", rs, "pbr");
    putFeature(features, "sectorCw5d", rs, "sector_cw_5d");
    putFeature(features, "vol20d", rs, "vol_20d");
    putFeature(features, "rsIdx20d", rs, "index_ret_20d");
    CandidateRow row = CandidateRow.builder()
        .ticker(rs.getString("ticker"))
        .quantScore(round6(rs.getDouble("score")))
        .stockName(rs.getString("stock_name"))
        .marketType(rs.getString("market_type"))
        .benchIndexCode(rs.getString("bench_index_code"))
        .sectorCode(rs.getString("sector_code"))
        .sectorName(rs.getString("sector_name"))
        .signals(signalValues)
        .features(features)
        .appliedLessonIds(List.of())
        .refRawClose(rs.getBigDecimal("raw_close"))
        .refAdjClose(nullable(rs, "adj_close"))
        .build();
    return new RowHolder(row, rs.getInt("universe_size"), rs.getInt("cut_size"));
  }

  private static void putFeature(Map<String, Object> features, String key, ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    if (value == null) {
      return;
    }
    if (value instanceof Number n) {
      features.put(key, round6(n.doubleValue()));
    } else {
      features.put(key, value);
    }
  }

  private static Double nullable(ResultSet rs, String column) throws SQLException {
    double v = rs.getDouble(column);
    return rs.wasNull() ? null : v;
  }

  static double round4(double v) {
    return Math.round(v * 1e4) / 1e4;
  }

  static double round6(double v) {
    return Math.round(v * 1e6) / 1e6;
  }
}

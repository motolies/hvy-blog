package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures.FlowFeature;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures.GlobalFeature;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures.IndexFeature;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures.SectorFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 시장 국면 특징 SQL (지수·시장 수급·해외·섹터·σ·규칙 추세·데이터 기준일·적용 구간). 프롬프트 market/sectors/dataAsOf/window 블록과 국면 채점 밴드의 원천이다.
 * 모든 조회는 기준일 이하만 본다. 해외는 화~토 06:30 수집이라 국내 18:30 판단 시점에 T-1 미국 종가까지 있다(누수 없음).
 */
@Service
@RequiredArgsConstructor
public class MarketFeatureService {

  static final List<String> INDEX_CODES = List.of("0001", "1001", "2001");
  static final List<String> BENCH_CODES = List.of("0001", "1001");
  static final List<String> GLOBAL_SYMBOLS = List.of("SPX", "COMP", "SOX", ".DJI", "FX@KRW");
  /** 데이터 기준일(globalAsOf) 판정에 쓰는 지수 심볼 — 환율은 미국 휴장일에도 갱신되므로 뺀다 */
  static final List<String> GLOBAL_INDEX_SYMBOLS = List.of("SPX", "COMP", "SOX", ".DJI");
  static final int TOP_SECTORS = 8;
  static final int BOTTOM_SECTORS = 3;
  static final int MIN_SECTOR_MEMBERS = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final AdvisorProperties properties;
  private final MarketTrendService trendService;
  private final TradingCalendar tradingCalendar;
  private final GlobalLinkService globalLinks;

  public MarketFeatures features(LocalDate asOf) {
    Map<String, Object> p = Map.of("d", asOf);
    List<IndexFeature> indices = jdbc.query("SELECT i.index_code, im.index_name, i.close_value, i.ret_1d, i.ret_5d, i.ret_20d, i.ret_60d, "
            + "i.close_value / NULLIF(i.ma_20, 0) - 1 AS dist_ma20, i.close_value / NULLIF(i.ma_60, 0) - 1 AS dist_ma60 "
            + "FROM mv_stock_index_metric i LEFT JOIN tb_stock_index_master im ON im.index_code = i.index_code "
            + "WHERE i.trade_date = :d AND i.index_code IN (:codes) ORDER BY i.index_code",
        Map.of("d", asOf, "codes", INDEX_CODES),
        (rs, i) -> new IndexFeature(rs.getString("index_code"), rs.getString("index_name"), rs.getDouble("close_value"),
            d(rs.getObject("ret_1d")), d(rs.getObject("ret_5d")), d(rs.getObject("ret_20d")), d(rs.getObject("ret_60d")),
            d(rs.getObject("dist_ma20")), d(rs.getObject("dist_ma60"))));

    List<FlowFeature> flows = jdbc.query("SELECT market_type, "
            + "SUM(CASE WHEN rn = 1 THEN foreign_net_amt END) AS frgn1, SUM(CASE WHEN rn = 1 THEN institution_net_amt END) AS inst1, "
            + "SUM(CASE WHEN rn = 1 THEN individual_net_amt END) AS indi1, "
            + "SUM(foreign_net_amt) AS frgn5, SUM(institution_net_amt) AS inst5, SUM(individual_net_amt) AS indi5 "
            + "FROM (SELECT market_type, foreign_net_amt, institution_net_amt, individual_net_amt, "
            + "      ROW_NUMBER() OVER (PARTITION BY market_type ORDER BY trade_date DESC) AS rn "
            + "      FROM tb_stock_market_investor_daily WHERE trade_date <= :d AND trade_date > CAST(:d AS date) - INTERVAL '20 days') t "
            + "WHERE rn <= 5 GROUP BY market_type ORDER BY market_type", p,
        (rs, i) -> new FlowFeature(rs.getString("market_type"), l(rs.getObject("frgn1")), l(rs.getObject("inst1")), l(rs.getObject("indi1")),
            l(rs.getObject("frgn5")), l(rs.getObject("inst5")), l(rs.getObject("indi5"))));

    // 미국은 현지일 < 기준일만 — 기준일 당일 세션은 19:30 판단 시점에 아직 열리지 않았다. (<= 로 두면 사후 재실행(baseDate=)에서 밤사이 결과가 새어 든다)
    List<GlobalFeature> global = jdbc.query("SELECT symbol, trade_date, close_price, "
            + "close_price / NULLIF(LAG(close_price, 1) OVER w, 0) - 1 AS r1, close_price / NULLIF(LAG(close_price, 5) OVER w, 0) - 1 AS r5, "
            + "close_price / NULLIF(LAG(close_price, 20) OVER w, 0) - 1 AS r20, close_price / NULLIF(LAG(close_price, 60) OVER w, 0) - 1 AS r60, "
            + "ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn "
            + "FROM tb_stock_global_market_daily WHERE symbol IN (:symbols) AND trade_date < :d AND trade_date > CAST(:d AS date) - INTERVAL '120 days' "
            + "WINDOW w AS (PARTITION BY symbol ORDER BY trade_date)",
        Map.of("d", asOf, "symbols", GLOBAL_SYMBOLS),
        (rs, i) -> rs.getInt("rn") == 1
            ? new GlobalFeature(rs.getString("symbol"), rs.getObject("trade_date", LocalDate.class), rs.getDouble("close_price"),
                d(rs.getObject("r1")), d(rs.getObject("r5")), d(rs.getObject("r20")), d(rs.getObject("r60")))
            : null)
        .stream().filter(g -> g != null).toList();
    // 지수 심볼 중 가장 오래된 날짜 — 하나라도 뒤처졌으면 그 블록 전체를 오래된 것으로 본다
    LocalDate globalAsOf = global.stream().filter(g -> GLOBAL_INDEX_SYMBOLS.contains(g.symbol())).map(GlobalFeature::date)
        .min(Comparator.naturalOrder()).orElse(null);
    Integer globalAge = globalAsOf == null ? null : tradingCalendar.tradingDaysBetween(globalAsOf, asOf);
    LocalDate flowAsOf = jdbc.queryForObject("SELECT MAX(trade_date) FROM tb_stock_market_investor_daily WHERE trade_date <= :d", p, LocalDate.class);
    LocalDate sectorAsOf = jdbc.queryForObject("SELECT MAX(trade_date) FROM mv_stock_sector_daily WHERE trade_date <= :d", p, LocalDate.class);

    // 동일가중 등락 합 — SECTOR_STRENGTH 시그널과 같은 정의 (시총가중은 밸류 스냅샷이 없는 과거에 NULL)
    List<SectorFeature> sectors = jdbc.query("SELECT s.sector_code, n.sector_name, SUM(s.avg_change_rate) AS cw5, "
            + "AVG(s.rising_ratio) AS rising, MAX(CASE WHEN s.rn = 1 THEN s.near_high_ratio END) AS near_high, "
            + "SUM(s.foreign_net_sum) AS frgn5, MAX(CASE WHEN s.rn = 1 THEN s.member_count END) AS members "
            + "FROM (SELECT sector_code, trade_date, avg_change_rate, rising_ratio, near_high_ratio, foreign_net_sum, member_count, "
            + "      ROW_NUMBER() OVER (PARTITION BY sector_code ORDER BY trade_date DESC) AS rn "
            + "      FROM mv_stock_sector_daily WHERE trade_date <= :d AND trade_date > CAST(:d AS date) - INTERVAL '20 days') s "
            + "LEFT JOIN (SELECT DISTINCT ON (sector_code) sector_code, sector_name FROM tb_stock_sector_map WHERE source = 'KRX' AND valid_to IS NULL "
            + "           ORDER BY sector_code, valid_from DESC) n ON n.sector_code = s.sector_code "
            + "WHERE s.rn <= 5 GROUP BY s.sector_code, n.sector_name HAVING MAX(CASE WHEN s.rn = 1 THEN s.member_count END) >= :minMembers "
            + "ORDER BY cw5 DESC NULLS LAST",
        Map.of("d", asOf, "minMembers", MIN_SECTOR_MEMBERS),
        (rs, i) -> new SectorFeature(rs.getString("sector_code"), rs.getString("sector_name"), d(rs.getObject("cw5")), d(rs.getObject("rising")),
            d(rs.getObject("near_high")), l(rs.getObject("frgn5")), rs.getInt("members")));
    List<SectorFeature> top = new ArrayList<>(sectors.subList(0, Math.min(TOP_SECTORS, sectors.size())));
    List<SectorFeature> bottom = new ArrayList<>();
    for (int i = sectors.size() - 1; i >= 0 && bottom.size() < BOTTOM_SECTORS && i >= TOP_SECTORS; i--) {
      bottom.add(sectors.get(i));
    }

    Map<String, Double> sigma = new LinkedHashMap<>();
    jdbc.query("SELECT index_code, STDDEV_SAMP(ret_1d) * SQRT(5) AS sigma5 FROM (SELECT index_code, ret_1d, "
            + "ROW_NUMBER() OVER (PARTITION BY index_code ORDER BY trade_date DESC) AS rn FROM mv_stock_index_metric "
            + "WHERE trade_date <= :d AND index_code IN (:codes)) t WHERE rn <= :n GROUP BY index_code",
        Map.of("d", asOf, "codes", BENCH_CODES, "n", properties.getScoring().getSigmaLookbackDays()),
        rs -> {
          sigma.put(rs.getString("index_code"), rs.getDouble("sigma5"));
        });

    TradingCalendar.Window window = tradingCalendar.window(asOf, properties.getHorizonDays());
    return new MarketFeatures(asOf, indices, flows, global, top, bottom, sigma, globalAsOf, globalAge, flowAsOf, sectorAsOf,
        window.entry(), window.exit(), trendService.trends(asOf), globalLinks.links(asOf));
  }

  private static Double d(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }

  private static Long l(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }
}

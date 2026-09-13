package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.MetricColumn;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Slack 채팅 봇 종목 도구용 읽기 SQL — 종목명·코드 해석, 종목 스냅샷, 지표 상위 N. stock 테이블을 읽지만 advisor 전용 질의라 advisor 쪽에 둔다(FeatureSql 과 같은 방식).
 * <p>
 * 가격은 정본인 {@code adj_close}(tb_stock_daily_metric, 수정 종가)만 쓴다. 상위 N 의 ORDER BY 컬럼은 {@link MetricColumn#getColumn()} 만 보간한다 —
 * 모델이 준 문자열은 enum 을 통과해야만 SQL 에 닿는다.
 */
@Repository
@RequiredArgsConstructor
public class StockLookupReader {

  /** 종목 해석 결과 1행 */
  public record StockHit(String ticker, String name, String market, String group, boolean active, LocalDate delistingDate) {
  }

  /** 종목 스냅샷 — 지표 행 + 밸류 + 섹터 */
  @Builder
  public record Snapshot(String ticker, String name, String market, LocalDate tradeDate, Double adjClose, Double ret1d, Double ret5d, Double ret20d,
                         Double ret60d, Double ret120d, Double ma5, Double ma20, Double ma60, Double ma120, Double distMa20, Double distMa60,
                         Double high52w, Double distHigh52w, Double tvAvg5d, Double tvRatio560, Double foreignNet5d, Double institutionNet5d,
                         Long marketCap, Double per, Double pbr, Double foreignHoldRate, LocalDate valuationDate, String sectorCode, String sectorName) {
  }

  /** 지표 상위 N 1행 */
  public record MetricRank(String ticker, String name, String market, Double value) {
  }

  private final JdbcTemplate jdbc;

  /**
   * 코드 정확 일치 → 이름 정확 일치 → 이름 접두 → 이름 포함 순. ~2,700행이라 시퀀스 스캔을 허용한다. 활성 종목 우선.
   */
  public List<StockHit> resolve(String query, int limit) {
    String q = query == null ? "" : query.trim();
    if (q.isEmpty()) {
      return List.of();
    }
    String like = "%" + escapeLike(q) + "%";
    String prefix = escapeLike(q) + "%";
    return jdbc.query("SELECT ticker, stock_name, market_type, security_group, is_active, delisting_date FROM tb_stock_master "
            + "WHERE ticker = ? OR stock_name ILIKE ? ESCAPE '\\' "
            + "ORDER BY is_active DESC, (ticker = ?) DESC, (stock_name = ?) DESC, (stock_name ILIKE ? ESCAPE '\\') DESC, stock_name LIMIT ?",
        HIT_MAPPER, q, like, q, q, prefix, limit);
  }

  /**
   * 기준일 이하의 지표 마지막 거래일. 지표가 없으면 빈 Optional.
   */
  public Optional<LocalDate> latestMetricDate(LocalDate onOrBefore) {
    return Optional.ofNullable(jdbc.queryForObject("SELECT MAX(trade_date) FROM tb_stock_daily_metric WHERE trade_date <= ?", LocalDate.class, onOrBefore));
  }

  /**
   * 종목의 기준일 이하 마지막 지표 행 + 그 시점 이하 마지막 밸류 스냅샷 + 현재 KRX 섹터.
   */
  public Optional<Snapshot> snapshot(String ticker, LocalDate asOf) {
    return jdbc.query("SELECT m.ticker, ms.stock_name, ms.market_type, m.trade_date, m.adj_close, m.ret_1d, m.ret_5d, m.ret_20d, m.ret_60d, m.ret_120d, "
            + "m.ma_5, m.ma_20, m.ma_60, m.ma_120, m.dist_ma20, m.dist_ma60, m.high_52w, m.dist_high_52w, m.tv_avg_5d, m.tv_ratio_5_60, "
            + "m.foreign_net_5d, m.institution_net_5d, v.market_cap, v.per, v.pbr, v.foreign_hold_rate, v.trade_date AS valuation_date, "
            + "sm.sector_code, sm.sector_name "
            + "FROM tb_stock_daily_metric m "
            + "JOIN tb_stock_master ms ON ms.ticker = m.ticker "
            + "LEFT JOIN tb_stock_valuation_daily v ON v.ticker = m.ticker "
            + "  AND v.trade_date = (SELECT MAX(trade_date) FROM tb_stock_valuation_daily WHERE ticker = m.ticker AND trade_date <= m.trade_date) "
            + "LEFT JOIN tb_stock_sector_map sm ON sm.ticker = m.ticker AND sm.source = 'KRX' AND sm.valid_to IS NULL "
            + "WHERE m.ticker = ? AND m.trade_date = (SELECT MAX(trade_date) FROM tb_stock_daily_metric WHERE ticker = ? AND trade_date <= ?) "
            + "ORDER BY sm.valid_from DESC NULLS LAST LIMIT 1",
        SNAPSHOT_MAPPER, ticker, ticker, asOf).stream().findFirst();
  }

  /**
   * 기준 거래일의 지표 상위 N (유니버스 뷰: 주권·활성·시총 1,000억 이상·거래대금 10억 이상). 컬럼·방향은 enum 에서만 온다.
   */
  public List<MetricRank> topN(MetricColumn column, boolean descending, String marketCode, int limit, LocalDate tradeDate) {
    String col = "m." + column.getColumn();
    StringBuilder sql = new StringBuilder("SELECT m.ticker, ms.stock_name, ms.market_type, " + col + " AS v FROM tb_stock_daily_metric m "
        + "JOIN tb_stock_master ms ON ms.ticker = m.ticker "
        + "JOIN vw_stock_universe_daily u ON u.ticker = m.ticker AND u.trade_date = m.trade_date "
        + "WHERE m.trade_date = ? AND " + col + " IS NOT NULL");
    if (marketCode != null && !marketCode.isBlank()) {
      sql.append(" AND ms.market_type = ?");
      sql.append(" ORDER BY ").append(col).append(descending ? " DESC" : " ASC").append(", m.ticker LIMIT ?");
      return jdbc.query(sql.toString(), RANK_MAPPER, tradeDate, marketCode, limit);
    }
    sql.append(" ORDER BY ").append(col).append(descending ? " DESC" : " ASC").append(", m.ticker LIMIT ?");
    return jdbc.query(sql.toString(), RANK_MAPPER, tradeDate, limit);
  }

  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  static final RowMapper<StockHit> HIT_MAPPER = (rs, i) -> new StockHit(rs.getString("ticker"), rs.getString("stock_name"), rs.getString("market_type"),
      rs.getString("security_group"), rs.getBoolean("is_active"), rs.getObject("delisting_date", LocalDate.class));

  static final RowMapper<MetricRank> RANK_MAPPER = (rs, i) -> new MetricRank(rs.getString("ticker"), rs.getString("stock_name"), rs.getString("market_type"),
      AdvisorJdbc.nullableDouble(rs, "v"));

  static final RowMapper<Snapshot> SNAPSHOT_MAPPER = (rs, i) -> Snapshot.builder()
      .ticker(rs.getString("ticker"))
      .name(rs.getString("stock_name"))
      .market(rs.getString("market_type"))
      .tradeDate(rs.getObject("trade_date", LocalDate.class))
      .adjClose(AdvisorJdbc.nullableDouble(rs, "adj_close"))
      .ret1d(AdvisorJdbc.nullableDouble(rs, "ret_1d"))
      .ret5d(AdvisorJdbc.nullableDouble(rs, "ret_5d"))
      .ret20d(AdvisorJdbc.nullableDouble(rs, "ret_20d"))
      .ret60d(AdvisorJdbc.nullableDouble(rs, "ret_60d"))
      .ret120d(AdvisorJdbc.nullableDouble(rs, "ret_120d"))
      .ma5(AdvisorJdbc.nullableDouble(rs, "ma_5"))
      .ma20(AdvisorJdbc.nullableDouble(rs, "ma_20"))
      .ma60(AdvisorJdbc.nullableDouble(rs, "ma_60"))
      .ma120(AdvisorJdbc.nullableDouble(rs, "ma_120"))
      .distMa20(AdvisorJdbc.nullableDouble(rs, "dist_ma20"))
      .distMa60(AdvisorJdbc.nullableDouble(rs, "dist_ma60"))
      .high52w(AdvisorJdbc.nullableDouble(rs, "high_52w"))
      .distHigh52w(AdvisorJdbc.nullableDouble(rs, "dist_high_52w"))
      .tvAvg5d(AdvisorJdbc.nullableDouble(rs, "tv_avg_5d"))
      .tvRatio560(AdvisorJdbc.nullableDouble(rs, "tv_ratio_5_60"))
      .foreignNet5d(AdvisorJdbc.nullableDouble(rs, "foreign_net_5d"))
      .institutionNet5d(AdvisorJdbc.nullableDouble(rs, "institution_net_5d"))
      .marketCap(AdvisorJdbc.nullableLong(rs, "market_cap"))
      .per(AdvisorJdbc.nullableDouble(rs, "per"))
      .pbr(AdvisorJdbc.nullableDouble(rs, "pbr"))
      .foreignHoldRate(AdvisorJdbc.nullableDouble(rs, "foreign_hold_rate"))
      .valuationDate(rs.getObject("valuation_date", LocalDate.class))
      .sectorCode(rs.getString("sector_code"))
      .sectorName(rs.getString("sector_name"))
      .build();
}

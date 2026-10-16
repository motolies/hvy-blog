package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.MetricColumn;
import kr.hvy.blog.modules.advisor.domain.code.ScreenOp;
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

  /** screenStocks 필터 1개 — 컬럼·연산자는 allowlist enum, 값은 바인딩 */
  public record ScreenFilter(MetricColumn column, ScreenOp op, double value) {
  }

  /** screenStocks 결과 행: 필터·정렬에 쓰인 컬럼 값(컬럼명 → 값, 원 단위 그대로) */
  public record ScreenRow(String ticker, String name, String market, Map<String, Double> values) {
  }

  /** screenStocks 결과: 상위 limit 행과 조건을 만족한 총 수 */
  public record ScreenResult(List<ScreenRow> rows, int matched) {
  }

  private final JdbcTemplate jdbc;

  /**
   * 코드 정확 일치 → 이름 정확 일치 → 이름 접두 → 이름 포함 순, 같은 등급 안에서는 주권(ST) > ETF > ETN > 리츠, 활성 우선, 짧은 이름 순. ~2,700행이라 시퀀스 스캔을 허용한다.
   * 2026-10-16 이전엔 is_active 가 최우선이고 증권 그룹 정렬이 없어 "삼성" 이 ETN 8개로 차고 삼성전자가 빠졌다(P2). 정확히 일치한 상폐 종목은 사용자가 그 이름을 친 것이므로 접두 일치보다 앞.
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
            + "ORDER BY CASE WHEN ticker = ? THEN 0 WHEN stock_name = ? THEN 1 WHEN stock_name ILIKE ? ESCAPE '\\' THEN 2 ELSE 3 END, "
            + "CASE security_group WHEN 'ST' THEN 0 WHEN 'EF' THEN 1 WHEN 'EN' THEN 2 WHEN 'RT' THEN 3 ELSE 4 END, "
            + "is_active DESC, LENGTH(stock_name), stock_name LIMIT ?",
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

  /**
   * 기준 거래일의 다중 조건 스크리닝(MCP screenStocks, 2026-10-16): 유니버스 뷰(topN 과 같은 모집단) 안에서 필터를 전부 만족하는 종목을 정렬 컬럼 순으로 limit 개.
   * matched 는 조건 만족 총수(COUNT(*) OVER()). SQL 에 보간되는 것은 enum 의 컬럼명·연산자뿐이고 값·날짜·시장·limit 은 바인딩한다.
   */
  public ScreenResult screen(List<ScreenFilter> filters, MetricColumn sort, boolean descending, String marketCode, int limit, LocalDate tradeDate) {
    java.util.LinkedHashSet<MetricColumn> columns = new java.util.LinkedHashSet<>();
    columns.add(sort);
    filters.forEach(f -> columns.add(f.column()));
    String select = columns.stream().map(c -> "m." + c.getColumn()).collect(java.util.stream.Collectors.joining(", "));
    StringBuilder sql = new StringBuilder("SELECT m.ticker, ms.stock_name, ms.market_type, " + select + ", COUNT(*) OVER () AS matched "
        + "FROM tb_stock_daily_metric m JOIN tb_stock_master ms ON ms.ticker = m.ticker "
        + "JOIN vw_stock_universe_daily u ON u.ticker = m.ticker AND u.trade_date = m.trade_date "
        + "WHERE m.trade_date = ? AND m." + sort.getColumn() + " IS NOT NULL");
    List<Object> args = new ArrayList<>();
    args.add(tradeDate);
    if (marketCode != null && !marketCode.isBlank()) {
      sql.append(" AND ms.market_type = ?");
      args.add(marketCode);
    }
    for (ScreenFilter f : filters) {
      sql.append(" AND m.").append(f.column().getColumn()).append(" ").append(f.op().getSql()).append(" ?");
      args.add(f.value());
    }
    sql.append(" ORDER BY m.").append(sort.getColumn()).append(descending ? " DESC" : " ASC").append(" NULLS LAST, m.ticker LIMIT ?");
    args.add(limit);
    int[] matched = {0};
    List<ScreenRow> rows = jdbc.query(sql.toString(), (rs, i) -> {
      matched[0] = rs.getInt("matched");
      Map<String, Double> values = new java.util.LinkedHashMap<>();
      for (MetricColumn c : columns) {
        values.put(c.getColumn(), AdvisorJdbc.nullableDouble(rs, c.getColumn()));
      }
      return new ScreenRow(rs.getString("ticker"), rs.getString("stock_name"), rs.getString("market_type"), values);
    }, args.toArray());
    return new ScreenResult(rows, matched[0]);
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

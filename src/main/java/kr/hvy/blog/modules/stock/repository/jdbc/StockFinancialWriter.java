package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재무제표(tb_stock_financial) 정정 누적 저장. 같은 결산기의 값이 바뀌면 덮어쓰지 않고 revision_seq+1 로 새 행을 넣는다.
 * first_seen_at(처음 관측 시각)이 쌓일수록 진짜 point-in-time 이 된다.
 * <p>
 * 예외 하나: 확장 지표(성장성·수익성·안정성 9개)가 전부 NULL 인 기존 행에 핵심 값은 같고 확장 값만 처음 들어오면
 * 정정이 아니라 컬럼 승격(2026-09-08) 이행이므로 그 행에 채우고 리비전을 올리지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class StockFinancialWriter {

  private static final String LATEST_SQL = """
      SELECT DISTINCT ON (fiscal_period, period_type)
             fiscal_period, period_type, revision_seq, revenue, operating_profit, net_income, total_asset, total_equity,
             total_debt, roe, debt_ratio, revenue_growth, profit_growth,
             operating_profit_growth, equity_growth, asset_growth, roa, net_margin, gross_margin,
             current_ratio, quick_ratio, borrowing_dependency
      FROM tb_stock_financial
      WHERE ticker = ?
      ORDER BY fiscal_period, period_type, revision_seq DESC
      """;

  private static final String INSERT_SQL = """
      INSERT INTO tb_stock_financial
          (ticker, fiscal_period, period_type, revision_seq, disclosed_at, available_from, available_rule, first_seen_at,
           revenue, operating_profit, net_income, total_asset, total_equity, total_debt, roe, debt_ratio,
           revenue_growth, profit_growth,
           operating_profit_growth, equity_growth, asset_growth, roa, net_margin, gross_margin,
           current_ratio, quick_ratio, borrowing_dependency, raw_json, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, fiscal_period, period_type, revision_seq) DO NOTHING
      """;

  /** 확장 지표가 전부 NULL 인 최신 행에만 채운다 (경합 시 조건이 막는다) */
  private static final String FILL_SQL = """
      UPDATE tb_stock_financial
      SET operating_profit_growth = ?, equity_growth = ?, asset_growth = ?, roa = ?, net_margin = ?, gross_margin = ?,
          current_ratio = ?, quick_ratio = ?, borrowing_dependency = ?, raw_json = ?
      WHERE ticker = ? AND fiscal_period = ? AND period_type = ? AND revision_seq = ?
        AND operating_profit_growth IS NULL AND equity_growth IS NULL AND asset_growth IS NULL AND roa IS NULL
        AND net_margin IS NULL AND gross_margin IS NULL AND current_ratio IS NULL AND quick_ratio IS NULL
        AND borrowing_dependency IS NULL
      """;

  private final JdbcTemplate jdbcTemplate;

  /** 최신 리비전 요약 */
  record Latest(int revisionSeq, FinancialRow values) {
  }

  /** 삽입 대기 행 */
  private record Insert(int revisionSeq, FinancialRow row) {
  }

  /**
   * 종목의 결산기별 최신 리비전.
   */
  Map<String, Latest> latest(String ticker) {
    Map<String, Latest> result = new HashMap<>();
    jdbcTemplate.query(LATEST_SQL, rs -> {
      FinancialRow values = new FinancialRow(ticker, rs.getString("fiscal_period"), rs.getString("period_type"), null, null,
          null, longOrNull(rs.getObject("revenue")), longOrNull(rs.getObject("operating_profit")),
          longOrNull(rs.getObject("net_income")), longOrNull(rs.getObject("total_asset")),
          longOrNull(rs.getObject("total_equity")), longOrNull(rs.getObject("total_debt")),
          rs.getBigDecimal("roe"), rs.getBigDecimal("debt_ratio"), rs.getBigDecimal("revenue_growth"),
          rs.getBigDecimal("profit_growth"),
          rs.getBigDecimal("operating_profit_growth"), rs.getBigDecimal("equity_growth"), rs.getBigDecimal("asset_growth"),
          rs.getBigDecimal("roa"), rs.getBigDecimal("net_margin"), rs.getBigDecimal("gross_margin"),
          rs.getBigDecimal("current_ratio"), rs.getBigDecimal("quick_ratio"), rs.getBigDecimal("borrowing_dependency"),
          null);
      result.put(key(values.fiscalPeriod(), values.periodType()), new Latest(rs.getInt("revision_seq"), values));
    }, ticker);
    return result;
  }

  /**
   * 종목 1개의 결산기 행들을 반영한다: 신규는 seq 0, 값이 바뀐 결산기는 seq+1, 같으면 건너뜀,
   * 확장 지표만 처음 채워지는 행은 UPDATE(리비전 유지).
   *
   * @return 반영한 행 수 (신규 삽입 + 채움)
   */
  public int apply(String ticker, List<FinancialRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    Map<String, Latest> latest = latest(ticker);
    List<Insert> inserts = new ArrayList<>();
    int filled = 0;
    for (FinancialRow row : rows) {
      Latest current = latest.get(key(row.fiscalPeriod(), row.periodType()));
      if (current == null) {
        inserts.add(new Insert(0, row));
      } else if (row.sameValues(current.values())) {
        continue;
      } else if (row.sameCoreValues(current.values()) && !current.values().hasExtendedMetrics() && row.hasExtendedMetrics()) {
        filled += fill(current.revisionSeq(), row);
      } else {
        inserts.add(new Insert(current.revisionSeq() + 1, row));
      }
    }
    return filled + insert(inserts);
  }

  /**
   * 종목의 리비전 수 합계 (검증용).
   */
  public int count(String ticker) {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_stock_financial WHERE ticker = ?", Integer.class, ticker);
    return count == null ? 0 : count;
  }

  private int insert(List<Insert> inserts) {
    if (inserts.isEmpty()) {
      return 0;
    }
    int[] counts = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(PreparedStatement ps, int i) throws SQLException {
        bindInsert(ps, inserts.get(i));
      }

      @Override
      public int getBatchSize() {
        return inserts.size();
      }
    });
    int inserted = 0;
    for (int count : counts) {
      inserted += Math.max(count, 0);
    }
    return inserted;
  }

  /**
   * INSERT_SQL 의 ? 순서대로 바인딩한다 (first_seen_at·created_at 은 NOW()).
   */
  private static void bindInsert(PreparedStatement ps, Insert insert) throws SQLException {
    FinancialRow row = insert.row();
    int i = 1;
    ps.setString(i++, row.ticker());
    ps.setString(i++, row.fiscalPeriod());
    ps.setString(i++, row.periodType());
    ps.setInt(i++, insert.revisionSeq());
    ps.setObject(i++, row.disclosedAt());
    ps.setObject(i++, row.availableFrom());
    ps.setString(i++, row.availableRule());
    ps.setObject(i++, row.revenue());
    ps.setObject(i++, row.operatingProfit());
    ps.setObject(i++, row.netIncome());
    ps.setObject(i++, row.totalAsset());
    ps.setObject(i++, row.totalEquity());
    ps.setObject(i++, row.totalDebt());
    ps.setBigDecimal(i++, row.roe());
    ps.setBigDecimal(i++, row.debtRatio());
    ps.setBigDecimal(i++, row.revenueGrowth());
    ps.setBigDecimal(i++, row.netIncomeGrowth());
    i = bindExtended(ps, i, row);
    ps.setObject(i, row.rawJson(), Types.OTHER);
  }

  /**
   * 확장 지표 9개를 채운다. 확장 지표가 전부 NULL 인 최신 행에만 적용된다.
   */
  private int fill(int revisionSeq, FinancialRow row) {
    return jdbcTemplate.update(FILL_SQL, ps -> {
      int i = bindExtended(ps, 1, row);
      ps.setObject(i++, row.rawJson(), Types.OTHER);
      ps.setString(i++, row.ticker());
      ps.setString(i++, row.fiscalPeriod());
      ps.setString(i++, row.periodType());
      ps.setInt(i, revisionSeq);
    });
  }

  /**
   * 확장 지표 9개를 from 부터 순서대로 바인딩하고 다음 인덱스를 돌려준다.
   */
  private static int bindExtended(PreparedStatement ps, int from, FinancialRow row) throws SQLException {
    int i = from;
    ps.setBigDecimal(i++, row.operatingProfitGrowth());
    ps.setBigDecimal(i++, row.equityGrowth());
    ps.setBigDecimal(i++, row.assetGrowth());
    ps.setBigDecimal(i++, row.roa());
    ps.setBigDecimal(i++, row.netMargin());
    ps.setBigDecimal(i++, row.grossMargin());
    ps.setBigDecimal(i++, row.currentRatio());
    ps.setBigDecimal(i++, row.quickRatio());
    ps.setBigDecimal(i++, row.borrowingDependency());
    return i;
  }

  private static String key(String fiscalPeriod, String periodType) {
    return fiscalPeriod + "/" + periodType;
  }

  private static Long longOrNull(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }
}

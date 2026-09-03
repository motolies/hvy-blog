package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재무제표(tb_stock_financial) 정정 누적 저장. 같은 결산기의 값이 바뀌면 덮어쓰지 않고 revision_seq+1 로 새 행을 넣는다.
 * first_seen_at(처음 관측 시각)이 쌓일수록 진짜 point-in-time 이 된다.
 */
@Repository
@RequiredArgsConstructor
public class StockFinancialWriter {

  private static final String LATEST_SQL = """
      SELECT DISTINCT ON (fiscal_period, period_type)
             fiscal_period, period_type, revision_seq, revenue, operating_profit, net_income, total_asset, total_equity,
             total_debt, roe, debt_ratio, revenue_growth, profit_growth
      FROM tb_stock_financial
      WHERE ticker = ?
      ORDER BY fiscal_period, period_type, revision_seq DESC
      """;

  private static final String INSERT_SQL = """
      INSERT INTO tb_stock_financial
          (ticker, fiscal_period, period_type, revision_seq, disclosed_at, available_from, available_rule, first_seen_at,
           revenue, operating_profit, net_income, total_asset, total_equity, total_debt, roe, debt_ratio,
           revenue_growth, profit_growth, raw_json, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, fiscal_period, period_type, revision_seq) DO NOTHING
      """;

  private final JdbcTemplate jdbcTemplate;

  /** 최신 리비전 요약 */
  record Latest(int revisionSeq, FinancialRow values) {
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
          rs.getBigDecimal("profit_growth"), null);
      result.put(key(values.fiscalPeriod(), values.periodType()), new Latest(rs.getInt("revision_seq"), values));
    }, ticker);
    return result;
  }

  /**
   * 종목 1개의 결산기 행들을 반영한다: 신규는 seq 0, 값이 바뀐 결산기는 seq+1, 같으면 건너뜀.
   *
   * @return 새로 넣은 행 수
   */
  public int apply(String ticker, List<FinancialRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    Map<String, Latest> latest = latest(ticker);
    List<Object[]> inserts = new ArrayList<>();
    for (FinancialRow row : rows) {
      Latest current = latest.get(key(row.fiscalPeriod(), row.periodType()));
      int seq;
      if (current == null) {
        seq = 0;
      } else if (row.sameValues(current.values())) {
        continue;
      } else {
        seq = current.revisionSeq() + 1;
      }
      inserts.add(new Object[]{row.ticker(), row.fiscalPeriod(), row.periodType(), seq, row.disclosedAt(),
          row.availableFrom(), row.availableRule(), row.revenue(), row.operatingProfit(), row.netIncome(),
          row.totalAsset(), row.totalEquity(), row.totalDebt(), row.roe(), row.debtRatio(), row.revenueGrowth(),
          row.profitGrowth(), row.rawJson()});
    }
    if (inserts.isEmpty()) {
      return 0;
    }
    int[] counts = jdbcTemplate.batchUpdate(INSERT_SQL, inserts, inserts.size(), (ps, args) -> {
      for (int i = 0; i < args.length; i++) {
        if (i == 17) {
          ps.setObject(i + 1, args[i], Types.OTHER);
        } else if (args[i] instanceof LocalDate date) {
          ps.setObject(i + 1, date);
        } else {
          ps.setObject(i + 1, args[i]);
        }
      }
    })[0];
    int inserted = 0;
    for (int count : counts) {
      inserted += Math.max(count, 0);
    }
    return inserted;
  }

  /**
   * 종목의 리비전 수 합계 (검증용).
   */
  public int count(String ticker) {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_stock_financial WHERE ticker = ?", Integer.class, ticker);
    return count == null ? 0 : count;
  }

  private static String key(String fiscalPeriod, String periodType) {
    return fiscalPeriod + "/" + periodType;
  }

  private static Long longOrNull(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }
}

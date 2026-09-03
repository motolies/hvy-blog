package kr.hvy.blog.modules.stock.repository.jdbc;

import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.code.EnumCodes;
import kr.hvy.blog.modules.stock.domain.model.AdjustEventRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 수정주가 계수 이벤트(tb_stock_adjust_event) upsert·조회. 계수가 바뀌면 verified 를 false 로 되돌린다.
 */
@Repository
@RequiredArgsConstructor
public class AdjustEventWriter {

  static final int PARAMS_PER_ROW = 5;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_adjust_event (ticker, effective_date, action_type, price_factor, volume_factor, verified, created_at)
      VALUES (?, ?, ?, ?, ?, FALSE, NOW())
      ON CONFLICT (ticker, effective_date, action_type) DO UPDATE SET
          price_factor  = EXCLUDED.price_factor,
          volume_factor = EXCLUDED.volume_factor,
          verified      = FALSE
      WHERE (tb_stock_adjust_event.price_factor, tb_stock_adjust_event.volume_factor)
            IS DISTINCT FROM (EXCLUDED.price_factor, EXCLUDED.volume_factor)
      """;

  private final JdbcTemplate jdbcTemplate;
  private final BatchUpsertSupport upsertSupport;

  /**
   * 계수 이벤트를 upsert 하고 신규·변경 행 수를 돌려준다.
   */
  public int upsert(List<AdjustEventRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, (ps, row) -> {
      ps.setString(1, row.ticker());
      ps.setObject(2, row.effectiveDate());
      ps.setString(3, row.actionType().getCode());
      ps.setBigDecimal(4, row.priceFactor());
      ps.setBigDecimal(5, row.volumeFactor());
    });
  }

  /**
   * 종목의 모든 이벤트 검증 플래그를 바꾼다.
   */
  public int markVerified(String ticker, boolean verified) {
    return jdbcTemplate.update("UPDATE tb_stock_adjust_event SET verified = ? WHERE ticker = ?", verified, ticker);
  }

  /**
   * 이벤트 보유 종목 (최근 이벤트 순, 최대 limit).
   */
  public List<String> tickersWithEvents(int limit) {
    return jdbcTemplate.query(
        "SELECT ticker FROM tb_stock_adjust_event GROUP BY ticker ORDER BY MAX(effective_date) DESC, ticker LIMIT ?",
        (rs, i) -> rs.getString(1), limit);
  }

  /**
   * 종목의 이벤트 목록.
   */
  public List<AdjustEventRow> findByTicker(String ticker) {
    return jdbcTemplate.query(
        "SELECT ticker, effective_date, action_type, price_factor, volume_factor FROM tb_stock_adjust_event "
            + "WHERE ticker = ? ORDER BY effective_date",
        (rs, i) -> new AdjustEventRow(rs.getString(1), rs.getObject(2, LocalDate.class),
            EnumCodes.fromCode(CorporateActionType.class, rs.getString(3)), rs.getBigDecimal(4), rs.getBigDecimal(5)), ticker);
  }

  /**
   * 전체 이벤트 수.
   */
  public int count() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_stock_adjust_event", Integer.class);
    return count == null ? 0 : count;
  }
}

package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.code.EnumCodes;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 기업행사 원본(tb_stock_corporate_action) upsert·조회. raw_json 은 JSONB 로 보관해 해석 규칙이 바뀌어도 재파싱할 수 있다.
 */
@Repository
@RequiredArgsConstructor
public class CorporateActionWriter {

  static final int PARAMS_PER_ROW = 8;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_corporate_action
          (ticker, effective_date, action_type, ratio_before, ratio_after, cash_amount, source, raw_json, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, effective_date, action_type, source) DO UPDATE SET
          ratio_before = EXCLUDED.ratio_before,
          ratio_after  = EXCLUDED.ratio_after,
          cash_amount  = EXCLUDED.cash_amount,
          raw_json     = EXCLUDED.raw_json
      WHERE (tb_stock_corporate_action.ratio_before, tb_stock_corporate_action.ratio_after, tb_stock_corporate_action.cash_amount)
            IS DISTINCT FROM (EXCLUDED.ratio_before, EXCLUDED.ratio_after, EXCLUDED.cash_amount)
         OR tb_stock_corporate_action.raw_json IS DISTINCT FROM EXCLUDED.raw_json
      """;

  private static final RowMapper<CorporateActionRow> ROW_MAPPER = (rs, i) -> new CorporateActionRow(
      rs.getString("ticker"), rs.getObject("effective_date", LocalDate.class),
      EnumCodes.fromCode(CorporateActionType.class, rs.getString("action_type")),
      rs.getBigDecimal("ratio_before"), rs.getBigDecimal("ratio_after"), rs.getBigDecimal("cash_amount"),
      EnumCodes.fromCode(CorporateActionSource.class, rs.getString("source")), rs.getString("raw_json"));

  private final JdbcTemplate jdbcTemplate;
  private final BatchUpsertSupport upsertSupport;

  /**
   * 기업행사 행들을 upsert 하고 신규·변경 행 수를 돌려준다.
   */
  public int upsert(List<CorporateActionRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, (ps, row) -> {
      ps.setString(1, row.ticker());
      ps.setObject(2, row.effectiveDate());
      ps.setString(3, row.actionType().getCode());
      ps.setBigDecimal(4, row.ratioBefore());
      ps.setBigDecimal(5, row.ratioAfter());
      ps.setBigDecimal(6, row.cashAmount());
      ps.setString(7, row.source().getCode());
      ps.setObject(8, row.rawJson(), Types.OTHER);
    });
  }

  /**
   * 출처·유형별 기업행사 (계수 산출 입력).
   */
  public List<CorporateActionRow> find(CorporateActionSource source, Collection<CorporateActionType> types) {
    String placeholders = String.join(",", types.stream().map(t -> "?").toList());
    Object[] args = new Object[types.size() + 1];
    args[0] = source.getCode();
    int i = 1;
    for (CorporateActionType type : types) {
      args[i++] = type.getCode();
    }
    return jdbcTemplate.query(
        "SELECT ticker, effective_date, action_type, ratio_before, ratio_after, cash_amount, source, raw_json::text AS raw_json "
            + "FROM tb_stock_corporate_action WHERE source = ? AND action_type IN (" + placeholders + ") "
            + "ORDER BY ticker, effective_date", ROW_MAPPER, args);
  }

  /**
   * 기업행사에는 있지만 마스터에 없는 종목코드 (상폐·KONEX·비상장 혼재). 상폐 이력 보강(STOCK_INFO) 후보다.
   */
  public List<String> tickersMissingFromMaster() {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT c.ticker FROM tb_stock_corporate_action c "
            + "LEFT JOIN tb_stock_master m ON m.ticker = c.ticker WHERE m.ticker IS NULL ORDER BY c.ticker", String.class);
  }

  /**
   * 종목의 기업행사 전체 (검증·조회용).
   */
  public List<CorporateActionRow> findByTicker(String ticker) {
    return jdbcTemplate.query(
        "SELECT ticker, effective_date, action_type, ratio_before, ratio_after, cash_amount, source, raw_json::text AS raw_json "
            + "FROM tb_stock_corporate_action WHERE ticker = ? ORDER BY effective_date", ROW_MAPPER, ticker);
  }
}

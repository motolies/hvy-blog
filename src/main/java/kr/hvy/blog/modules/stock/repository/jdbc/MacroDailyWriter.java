package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 거시 지표 일별 값(tb_stock_macro_daily) 배치 upsert. 값이 정정되면 value·source·collected_at 만 바뀌고
 * {@code observed_at}(최초 수신 시각)·{@code available_from} 은 절대 갱신하지 않는다 — 룩어헤드 감사 기준이기 때문이다.
 */
@Repository
@RequiredArgsConstructor
public class MacroDailyWriter {

  static final int PARAMS_PER_ROW = 5;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_macro_daily (series_code, obs_date, value, source, available_from, observed_at, collected_at)
      VALUES (?, ?, ?, ?, ?, NOW(), NOW())
      ON CONFLICT (series_code, obs_date) DO UPDATE SET
          value        = EXCLUDED.value,
          source       = EXCLUDED.source,
          collected_at = NOW()
      WHERE tb_stock_macro_daily.value IS DISTINCT FROM EXCLUDED.value
      """;

  private final BatchUpsertSupport upsertSupport;
  private final JdbcTemplate jdbc;

  /**
   * 관측치를 넣고 새로 들어갔거나 값이 바뀐 행 수를 돌려준다.
   */
  public int upsert(List<MacroObservation> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, MacroDailyWriter::bind);
  }

  /**
   * 시리즈의 마지막 관측일 (없으면 null).
   */
  public LocalDate latestObsDate(MacroSeries series) {
    return jdbc.query("SELECT MAX(obs_date) AS d FROM tb_stock_macro_daily WHERE series_code = ?",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null, series.getCode());
  }

  private static void bind(PreparedStatement ps, MacroObservation row) throws SQLException {
    ps.setString(1, row.series().getCode());
    ps.setObject(2, row.obsDate());
    ps.setBigDecimal(3, row.value());
    ps.setString(4, row.source().getCode());
    ps.setObject(5, row.availableFrom());
  }
}

package kr.hvy.blog.modules.stock.repository.jdbc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.SectorMapRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 종목-섹터 매핑(tb_stock_sector_map) SCD 동기화. KRX 소스(지수업종 중분류)만 자동 관리하고
 * THEME/CUSTOM 행은 건드리지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class StockSectorMapWriter {

  static final int PARAMS_PER_ROW = 5;

  private static final String CURRENT_SQL = """
      SELECT ticker, sector_code FROM tb_stock_sector_map WHERE source = ? AND valid_to IS NULL
      """;

  private static final String DELETE_SAME_DAY_SQL = """
      DELETE FROM tb_stock_sector_map
      WHERE ticker = ? AND source = ? AND valid_to IS NULL AND valid_from = ?
      """;

  private static final String CLOSE_SQL = """
      UPDATE tb_stock_sector_map SET valid_to = ?
      WHERE ticker = ? AND source = ? AND valid_to IS NULL AND valid_from < ?
      """;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_sector_map (ticker, sector_code, valid_from, valid_to, sector_name, source, created_at)
      VALUES (?, ?, ?, NULL, ?, ?, NOW())
      ON CONFLICT (ticker, sector_code, valid_from) DO UPDATE SET
          valid_to    = NULL,
          sector_name = EXCLUDED.sector_name
      """;

  private final JdbcTemplate jdbcTemplate;
  private final BatchUpsertSupport upsertSupport;

  /**
   * 원하는 현재 매핑(종목당 1개)으로 동기화한다. 바뀐 종목은 기존 행을 닫고 새 행을 넣고,
   * desired 에 없는 종목의 현재 행은 닫는다(상장폐지·비활성).
   *
   * @return 새로 기록한 행 수
   */
  public int sync(List<SectorMapRow> desired, LocalDate asOf, String source) {
    Map<String, String> current = new HashMap<>();
    jdbcTemplate.query(CURRENT_SQL, rs -> {
      current.put(rs.getString(1), rs.getString(2));
    }, source);

    List<SectorMapRow> toInsert = new ArrayList<>();
    Map<String, SectorMapRow> desiredByTicker = new HashMap<>();
    for (SectorMapRow row : desired) {
      desiredByTicker.put(row.ticker(), row);
      if (!row.sectorCode().equals(current.get(row.ticker()))) {
        toInsert.add(row);
      }
    }
    List<String> toClose = new ArrayList<>();
    for (Map.Entry<String, String> entry : current.entrySet()) {
      SectorMapRow want = desiredByTicker.get(entry.getKey());
      if (want == null || !want.sectorCode().equals(entry.getValue())) {
        toClose.add(entry.getKey());
      }
    }
    if (!toClose.isEmpty()) {
      // 같은 날 만들어진 현재 행은 닫으면 길이 0 구간이 되므로 지운다
      jdbcTemplate.batchUpdate(DELETE_SAME_DAY_SQL, toClose, 500, (ps, ticker) -> {
        ps.setString(1, ticker);
        ps.setString(2, source);
        ps.setObject(3, asOf);
      });
      jdbcTemplate.batchUpdate(CLOSE_SQL, toClose, 500, (ps, ticker) -> {
        ps.setObject(1, asOf);
        ps.setString(2, ticker);
        ps.setString(3, source);
        ps.setObject(4, asOf);
      });
    }
    return upsertSupport.batchUpsert(UPSERT_SQL, toInsert, PARAMS_PER_ROW, (ps, row) -> {
      ps.setString(1, row.ticker());
      ps.setString(2, row.sectorCode());
      ps.setObject(3, row.validFrom());
      ps.setString(4, row.sectorName());
      ps.setString(5, row.source());
    });
  }
}

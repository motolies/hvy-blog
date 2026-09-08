package kr.hvy.blog.modules.stock.repository.jdbc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.hvy.blog.modules.stock.domain.model.SectorMapRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 종목-섹터 매핑(tb_stock_sector_map) SCD 동기화. 소스(KRX / THEME / CUSTOM)별로 따로 동기화하며 다른 소스 행은 건드리지 않는다.
 * <p>
 * 키는 (ticker, sector_code) 쌍이다. KRX 는 종목당 1개라 "섹터 이동 = 옛 쌍 닫고 새 쌍 삽입"이고,
 * THEME 은 종목당 여러 개(N:M)라 이탈한 테마 쌍만 닫힌다.
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
      WHERE ticker = ? AND sector_code = ? AND source = ? AND valid_to IS NULL AND valid_from = ?
      """;

  private static final String CLOSE_SQL = """
      UPDATE tb_stock_sector_map SET valid_to = ?
      WHERE ticker = ? AND sector_code = ? AND source = ? AND valid_to IS NULL AND valid_from < ?
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
   * 원하는 현재 매핑 집합으로 동기화한다. desired 에 없는 현재 쌍은 닫고(상장폐지·이탈), 현재에 없는 desired 쌍은 새 행으로 넣는다.
   *
   * @return 새로 기록한 행 수
   */
  public int sync(List<SectorMapRow> desired, LocalDate asOf, String source) {
    Set<String> current = new HashSet<>();
    jdbcTemplate.query(CURRENT_SQL, rs -> {
      current.add(key(rs.getString(1), rs.getString(2)));
    }, source);

    Set<String> wanted = new HashSet<>();
    List<SectorMapRow> toInsert = new ArrayList<>();
    for (SectorMapRow row : desired) {
      if (wanted.add(key(row.ticker(), row.sectorCode())) && !current.contains(key(row.ticker(), row.sectorCode()))) {
        toInsert.add(row);
      }
    }
    List<String[]> toClose = new ArrayList<>();
    for (String pair : current) {
      if (!wanted.contains(pair)) {
        toClose.add(pair.split("\\|", 2));
      }
    }
    if (!toClose.isEmpty()) {
      // 같은 날 만들어진 현재 행은 닫으면 길이 0 구간이 되므로 지운다
      jdbcTemplate.batchUpdate(DELETE_SAME_DAY_SQL, toClose, 500, (ps, pair) -> {
        ps.setString(1, pair[0]);
        ps.setString(2, pair[1]);
        ps.setString(3, source);
        ps.setObject(4, asOf);
      });
      jdbcTemplate.batchUpdate(CLOSE_SQL, toClose, 500, (ps, pair) -> {
        ps.setObject(1, asOf);
        ps.setString(2, pair[0]);
        ps.setString(3, pair[1]);
        ps.setString(4, source);
        ps.setObject(5, asOf);
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

  private static String key(String ticker, String sectorCode) {
    return ticker + "|" + sectorCode;
  }
}

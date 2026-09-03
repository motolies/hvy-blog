package kr.hvy.blog.modules.stock.repository.jdbc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.MasterHistoryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 종목 마스터 SCD2 이력(tb_stock_master_history).
 * <p>
 * 구간은 [valid_from, valid_to) 반열림이며 valid_to IS NULL 이 현재 행이다. 같은 날 재실행으로 값이 또 바뀌면
 * (ticker, valid_from) 충돌을 제자리 갱신으로 흡수한다.
 */
@Repository
@RequiredArgsConstructor
public class StockMasterHistoryWriter {

  static final int PARAMS_PER_ROW = 13;

  private static final String CLOSE_SQL = """
      UPDATE tb_stock_master_history SET valid_to = ?
      WHERE ticker = ? AND valid_to IS NULL AND valid_from < ?
      """;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_master_history
          (ticker, valid_from, valid_to, stock_name, market_type, security_group, sector_mid_code,
           is_kospi200, is_krx300, is_suspended, is_administrative, is_active, listed_shares, snapshot_hash, created_at)
      VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, valid_from) DO UPDATE SET
          valid_to          = NULL,
          stock_name        = EXCLUDED.stock_name,
          market_type       = EXCLUDED.market_type,
          security_group    = EXCLUDED.security_group,
          sector_mid_code   = EXCLUDED.sector_mid_code,
          is_kospi200       = EXCLUDED.is_kospi200,
          is_krx300         = EXCLUDED.is_krx300,
          is_suspended      = EXCLUDED.is_suspended,
          is_administrative = EXCLUDED.is_administrative,
          is_active         = EXCLUDED.is_active,
          listed_shares     = EXCLUDED.listed_shares,
          snapshot_hash     = EXCLUDED.snapshot_hash
      """;

  private final JdbcTemplate jdbcTemplate;
  private final BatchUpsertSupport upsertSupport;

  /**
   * 현재 행(valid_to IS NULL)의 ticker → snapshot_hash.
   */
  public Map<String, String> currentHashes() {
    Map<String, String> result = new HashMap<>();
    jdbcTemplate.query("SELECT ticker, snapshot_hash FROM tb_stock_master_history WHERE valid_to IS NULL",
        rs -> {
          result.put(rs.getString(1), rs.getString(2));
        });
    return result;
  }

  /**
   * 바뀐 종목들의 현재 행을 닫고 새 행을 넣는다.
   *
   * @return 새로 기록(또는 제자리 갱신)한 행 수
   */
  public int apply(List<MasterHistoryRow> changed) {
    if (changed.isEmpty()) {
      return 0;
    }
    jdbcTemplate.batchUpdate(CLOSE_SQL, changed, 500, (ps, row) -> {
      ps.setObject(1, row.validFrom());
      ps.setString(2, row.ticker());
      ps.setObject(3, row.validFrom());
    });
    return upsertSupport.batchUpsert(UPSERT_SQL, changed, PARAMS_PER_ROW, (ps, row) -> {
      ps.setString(1, row.ticker());
      ps.setObject(2, row.validFrom());
      ps.setString(3, row.stockName());
      ps.setString(4, row.marketType().getCode());
      ps.setString(5, row.securityGroup());
      ps.setString(6, row.sectorMidCode());
      ps.setBoolean(7, row.kospi200());
      ps.setBoolean(8, row.krx300());
      ps.setBoolean(9, row.suspended());
      ps.setBoolean(10, row.administrative());
      ps.setBoolean(11, row.active());
      ps.setObject(12, row.listedShares());
      ps.setString(13, row.snapshotHash());
    });
  }

  /**
   * 현재 행 수 (검증·테스트용).
   */
  public int countCurrent() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tb_stock_master_history WHERE valid_to IS NULL", Integer.class);
    return count == null ? 0 : count;
  }
}

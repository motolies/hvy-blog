package kr.hvy.blog.modules.stock.repository.jdbc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.masterfile.IndexCodeRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 업종·지수 코드 마스터(tb_stock_index_master). idxcode.mst 로 갱신하며 지수 백필의 대상 목록이 된다.
 */
@Repository
@RequiredArgsConstructor
public class MarketIndexMasterWriter {

  static final int PARAMS_PER_ROW = 3;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_index_master (index_code, market_div, index_name, is_active, collected_at)
      VALUES (?, ?, ?, TRUE, NOW())
      ON CONFLICT (index_code) DO UPDATE SET
          market_div   = EXCLUDED.market_div,
          index_name   = EXCLUDED.index_name,
          is_active    = TRUE,
          collected_at = NOW()
      WHERE (tb_stock_index_master.market_div, tb_stock_index_master.index_name, tb_stock_index_master.is_active)
            IS DISTINCT FROM (EXCLUDED.market_div, EXCLUDED.index_name, TRUE)
      """;

  private static final String DEACTIVATE_SQL = """
      UPDATE tb_stock_index_master SET is_active = FALSE, collected_at = NOW()
      WHERE is_active = TRUE AND NOT (index_code = ANY (?))
      """;

  private final JdbcTemplate jdbcTemplate;
  private final BatchUpsertSupport upsertSupport;

  /**
   * 파일에 있는 코드는 upsert(활성), 없는 코드는 비활성으로 돌린다.
   */
  public int replaceAll(List<IndexCodeRecord> records) {
    if (records.isEmpty()) {
      return 0;
    }
    int changed = upsertSupport.batchUpsert(UPSERT_SQL, records, PARAMS_PER_ROW, (ps, row) -> {
      ps.setString(1, row.indexCode());
      ps.setString(2, row.marketDiv());
      ps.setString(3, row.indexName());
    });
    String[] codes = records.stream().map(IndexCodeRecord::indexCode).toArray(String[]::new);
    jdbcTemplate.update(DEACTIVATE_SQL, ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", codes)));
    return changed;
  }

  /**
   * 활성 지수 코드 (오름차순).
   */
  public List<String> activeCodes() {
    return jdbcTemplate.query("SELECT index_code FROM tb_stock_index_master WHERE is_active = TRUE ORDER BY index_code",
        (rs, i) -> rs.getString(1));
  }

  /**
   * 코드 → 이름 (활성/비활성 모두).
   */
  public Map<String, String> names() {
    Map<String, String> result = new HashMap<>();
    jdbcTemplate.query("SELECT index_code, index_name FROM tb_stock_index_master", rs -> {
      result.put(rs.getString(1), rs.getString(2));
    });
    return result;
  }
}

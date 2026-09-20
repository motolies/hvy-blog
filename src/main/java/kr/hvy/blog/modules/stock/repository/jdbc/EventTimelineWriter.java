package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.EventTimelineRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 테마별 일별 사건 스트레스(tb_stock_event_timeline) 배치 upsert.
 * LIVE 행은 한 번 들어가면 재계산으로 덮어쓰지 않는다(당시 알 수 있었던 값 보존) — DO UPDATE 의 WHERE 가 BACKFILL 행만 허용한다.
 */
@Repository
@RequiredArgsConstructor
public class EventTimelineWriter {

  static final int PARAMS_PER_ROW = 7;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_event_timeline (theme_code, obs_date, source, article_vol, total_vol, vol_ratio, avg_tone, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (theme_code, obs_date, source) DO UPDATE SET
          article_vol  = EXCLUDED.article_vol,
          total_vol    = EXCLUDED.total_vol,
          vol_ratio    = EXCLUDED.vol_ratio,
          avg_tone     = EXCLUDED.avg_tone,
          collected_at = NOW()
      WHERE tb_stock_event_timeline.source = 'BACKFILL'
        AND (tb_stock_event_timeline.article_vol, tb_stock_event_timeline.total_vol, tb_stock_event_timeline.vol_ratio, tb_stock_event_timeline.avg_tone)
            IS DISTINCT FROM (EXCLUDED.article_vol, EXCLUDED.total_vol, EXCLUDED.vol_ratio, EXCLUDED.avg_tone)
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * 행들을 넣고 새로 들어갔거나(BACKFILL 이면) 바뀐 행 수를 돌려준다.
   */
  public int upsert(List<EventTimelineRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, EventTimelineWriter::bind);
  }

  private static void bind(PreparedStatement ps, EventTimelineRow row) throws SQLException {
    ps.setString(1, row.themeCode());
    ps.setObject(2, row.obsDate());
    ps.setString(3, row.source());
    setLong(ps, 4, row.articleVol());
    setLong(ps, 5, row.totalVol());
    setDouble(ps, 6, row.volRatio());
    setDouble(ps, 7, row.avgTone());
  }

  private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.DOUBLE);
    } else {
      ps.setDouble(index, value);
    }
  }
}

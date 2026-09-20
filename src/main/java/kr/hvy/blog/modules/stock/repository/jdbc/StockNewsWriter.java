package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 뉴스 제목 저장·조회 (tb_stock_news). 같은 (source, title_hash, published_at) 또는 같은 (source, serial_no) 는 무시한다 —
 * GDELT 는 같은 기사를 다른 seendate 로 재보고하므로 URL 해시(serial_no) 유니크가 두 번째 방어선이다. 유니크가 둘이라 ON CONFLICT 는 타깃 없이 쓴다
 * (타깃을 지정하면 다른 쪽 위반이 예외로 튄다).
 */
@Repository
@RequiredArgsConstructor
public class StockNewsWriter {

  static final int PARAMS_PER_ROW = 9;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_news (source, provider_code, serial_no, published_at, title, title_hash, category_code, origin, tickers, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT DO NOTHING
      """;

  private static final String COLUMNS = "news_id, source, provider_code, serial_no, published_at, title, title_hash, category_code, origin, tickers";

  private final BatchUpsertSupport upsertSupport;
  private final JdbcTemplate jdbc;

  /**
   * 제목 행들을 넣고 새로 들어간 행 수를 돌려준다.
   */
  public int upsert(List<NewsItem> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, StockNewsWriter::bind);
  }

  /**
   * (from, until] 사이에 작성된 제목, 최신순. until 이 판단 시각이라 그 뒤 기사는 절대 나오지 않는다(룩어헤드 방어).
   */
  public List<NewsItem> findPublishedBetween(Instant from, Instant until, int limit) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_stock_news WHERE published_at > ? AND published_at <= ? ORDER BY published_at DESC, news_id DESC LIMIT ?",
        MAPPER, OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(until, ZoneOffset.UTC), limit);
  }

  private static void bind(PreparedStatement ps, NewsItem row) throws SQLException {
    ps.setString(1, row.source());
    ps.setString(2, row.providerCode());
    ps.setString(3, row.serialNo());
    ps.setObject(4, OffsetDateTime.ofInstant(row.publishedAt(), ZoneOffset.UTC));
    ps.setString(5, row.title());
    ps.setBytes(6, row.titleHash());
    ps.setString(7, row.categoryCode());
    ps.setString(8, row.origin());
    Array tickers = ps.getConnection().createArrayOf("varchar", row.tickers() == null ? new String[0] : row.tickers().toArray(new String[0]));
    ps.setArray(9, tickers);
  }

  static final RowMapper<NewsItem> MAPPER = (rs, i) -> {
    Array array = rs.getArray("tickers");
    List<String> tickers = array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    return NewsItem.builder()
        .newsId(rs.getLong("news_id"))
        .source(rs.getString("source"))
        .providerCode(rs.getString("provider_code"))
        .serialNo(rs.getString("serial_no"))
        .publishedAt(rs.getObject("published_at", OffsetDateTime.class).toInstant())
        .title(rs.getString("title"))
        .titleHash(rs.getBytes("title_hash"))
        .categoryCode(rs.getString("category_code"))
        .origin(rs.getString("origin"))
        .tickers(tickers)
        .build();
  };
}

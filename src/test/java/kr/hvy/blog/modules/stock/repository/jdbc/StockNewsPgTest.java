package kr.hvy.blog.modules.stock.repository.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * tb_stock_news: 배열 컬럼·BYTEA 해시 왕복, (source, title_hash, published_at) 중복 무시, 판단 시각 경계(published_at ≤ until 만, 룩어헤드 방어).
 */
@Testcontainers
class StockNewsPgTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  private JdbcTemplate jdbc;
  private StockNewsWriter writer;

  @BeforeAll
  static void schema() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("db/stock-schema.sql"));
    }
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    writer = new StockNewsWriter(new BatchUpsertSupport(jdbc), jdbc);
    jdbc.update("TRUNCATE tb_stock_news");
  }

  @Test
  @DisplayName("저장·중복 무시·창 조회: 같은 제목·시각은 한 번만, until 이후 기사는 절대 나오지 않는다")
  void upsertAndWindow() {
    Instant until = Instant.parse("2026-09-11T10:30:00Z");
    List<NewsItem> rows = List.of(
        item(until.minusSeconds(60), "삼성전자, HBM4 양산", List.of("005930", "000660")),
        item(until.minusSeconds(60), "삼성전자 HBM4  양산!", List.of("005930")),      // 정규화 후 같은 제목·시각 → 무시
        item(until, "경계 기사 (판단 시각과 같음)", List.of()),                      // <= until 포함
        item(until.plusSeconds(1), "미래 기사", List.of()),                          // 제외
        item(until.minusSeconds(36 * 3600L), "창 밖 기사", List.of()));              // > from 이 아니라 제외
    assertThat(writer.upsert(rows)).isEqualTo(4);
    assertThat(writer.upsert(rows)).as("재실행은 전부 무시").isZero();

    List<NewsItem> window = writer.findPublishedBetween(until.minusSeconds(36 * 3600L), until, 100);
    assertThat(window).extracting(NewsItem::title).containsExactly("경계 기사 (판단 시각과 같음)", "삼성전자, HBM4 양산");
    assertThat(window.get(1).tickers()).containsExactly("005930", "000660");
    assertThat(window.get(1).titleHash()).isEqualTo(NewsItem.hashTitle("삼성전자, HBM4 양산"));
    assertThat(window.getFirst().tickers()).isEmpty();
    assertThat(writer.latestPublishedAt("KIS")).isEqualTo(until.plusSeconds(1));
    assertThat(writer.latestPublishedAt("OTHER")).isNull();
  }

  private static NewsItem item(Instant at, String title, List<String> tickers) {
    return NewsItem.builder().source("KIS").providerCode("2").serialNo("s" + at.getEpochSecond() + title.hashCode()).publishedAt(at).title(title)
        .titleHash(NewsItem.hashTitle(title)).categoryCode("01").origin("연합").tickers(tickers).build();
  }
}

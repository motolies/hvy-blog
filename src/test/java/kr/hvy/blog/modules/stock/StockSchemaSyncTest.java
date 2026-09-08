package kr.hvy.blog.modules.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * schema-postgres.sql 이 db/stock-*.sql 원본과 어긋나지 않는지 검사한다.
 * <p>
 * Flyway 가 없어 두 곳을 손으로 맞추는데, 테스트는 원본 세 파일만 로드하므로 복사본의 드리프트를 CI 가 못 잡았다.
 * 복사본은 "-- >>> BEGIN {원본}" / "-- <<< END {원본}" 마커로 감싼 원문이어야 하고, DROP 블록에 stock 테이블 전부가 있어야 한다.
 */
class StockSchemaSyncTest {

  private static final String MIRROR = "schema-postgres.sql";
  private static final List<String> SOURCES = List.of(
      "db/stock-schema.sql", "db/stock-derived.sql", "db/stock-seed.sql");
  private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE IF NOT EXISTS (\\w+)");
  private static final String REBUILD = "db/stock-derived-rebuild.sql";
  private static final Pattern CREATE_MV = Pattern.compile("^CREATE MATERIALIZED VIEW IF NOT EXISTS (\\w+)");
  private static final Pattern CREATE_VIEW = Pattern.compile("^CREATE OR REPLACE VIEW (\\w+)");

  @Test
  @DisplayName("마커 사이 내용이 원본 세 파일과 줄 단위로 같다")
  void mirrorBlocksMatchSources() throws IOException {
    List<String> mirror = lines(MIRROR);
    for (String source : SOURCES) {
      int begin = indexOfOnly(mirror, "-- >>> BEGIN " + source);
      int end = indexOfOnly(mirror, "-- <<< END " + source);
      assertThat(begin).as("%s BEGIN 마커", source).isGreaterThanOrEqualTo(0);
      assertThat(end).as("%s END 마커", source).isGreaterThan(begin);
      List<String> block = stripTrailingBlank(mirror.subList(begin + 1, end));
      assertThat(block)
          .as("%s 원문과 %s 복사본이 다르다 — 원본을 고쳤으면 복사본도 갱신한다", source, MIRROR)
          .containsExactlyElementsOf(lines(source));
    }
  }

  @Test
  @DisplayName("복사본 순서는 schema → derived → seed 다 (의존 순서)")
  void mirrorBlocksAreInDependencyOrder() throws IOException {
    List<String> mirror = lines(MIRROR);
    int previous = -1;
    for (String source : SOURCES) {
      int begin = indexOfOnly(mirror, "-- >>> BEGIN " + source);
      assertThat(begin).as("%s 는 앞 파일 뒤에 와야 한다", source).isGreaterThan(previous);
      previous = begin;
    }
  }

  @Test
  @DisplayName("DROP 블록이 stock 테이블 전부를 마커 앞에서 CASCADE 로 지운다")
  void dropBlockCoversEveryStockTable() throws IOException {
    List<String> tables = new ArrayList<>();
    for (String line : lines("db/stock-schema.sql")) {
      Matcher m = CREATE_TABLE.matcher(line);
      if (m.find()) {
        tables.add(m.group(1));
      }
    }
    assertThat(tables).hasSize(22).allMatch(t -> t.startsWith("tb_stock_"));

    List<String> mirror = lines(MIRROR);
    int firstBegin = indexOfOnly(mirror, "-- >>> BEGIN " + SOURCES.getFirst());
    List<String> head = mirror.subList(0, firstBegin);
    for (String table : tables) {
      assertThat(head)
          .as("%s 의 DROP 이 마커 앞 DROP 블록에 없다", table)
          .contains("DROP TABLE IF EXISTS " + table + " CASCADE;");
    }
  }

  @Test
  @DisplayName("파생 재구축 스크립트가 stock-derived.sql 의 MV·뷰 전부를 DROP 한다 (IF NOT EXISTS 는 기존 MV 를 못 바꾼다)")
  void rebuildScriptDropsEveryDerivedObject() throws IOException {
    List<String> rebuild = lines(REBUILD);
    int objects = 0;
    for (String line : lines("db/stock-derived.sql")) {
      Matcher mv = CREATE_MV.matcher(line);
      Matcher view = CREATE_VIEW.matcher(line);
      if (mv.find()) {
        objects++;
        assertThat(rebuild).as("%s 의 DROP 이 %s 에 없다", mv.group(1), REBUILD)
            .contains("DROP MATERIALIZED VIEW IF EXISTS " + mv.group(1) + ";");
      } else if (view.find()) {
        objects++;
        assertThat(rebuild).as("%s 의 DROP 이 %s 에 없다", view.group(1), REBUILD)
            .contains("DROP VIEW IF EXISTS " + view.group(1) + ";");
      }
    }
    assertThat(objects).isEqualTo(6); // MV 3 + 뷰 3 (종목 일별 지표는 테이블)
  }

  /**
   * 클래스패스 리소스를 줄 목록으로 읽는다. CRLF 는 LF 로 통일하고 끝의 빈 줄은 버린다.
   */
  private static List<String> lines(String resource) throws IOException {
    try (InputStream in = StockSchemaSyncTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("리소스 %s 가 없다", resource).isNotNull();
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
      return stripTrailingBlank(List.of(text.split("\n", -1)));
    }
  }

  /**
   * 정확히 한 번 나오는 줄의 위치를 돌려준다. 없으면 -1, 두 번 이상이면 실패.
   */
  private static int indexOfOnly(List<String> lines, String exact) {
    int found = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).equals(exact)) {
        assertThat(found).as("'%s' 가 두 번 이상 나온다", exact).isEqualTo(-1);
        found = i;
      }
    }
    return found;
  }

  private static List<String> stripTrailingBlank(List<String> lines) {
    int end = lines.size();
    while (end > 0 && lines.get(end - 1).isBlank()) {
      end--;
    }
    return new ArrayList<>(lines.subList(0, end));
  }
}

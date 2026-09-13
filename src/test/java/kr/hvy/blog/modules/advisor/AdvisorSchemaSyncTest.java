package kr.hvy.blog.modules.advisor;

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
 * schema-postgres.sql 이 db/advisor-*.sql 원본과 어긋나지 않는지 검사한다 (StockSchemaSyncTest 와 같은 규약).
 * <p>
 * 복사본은 "-- >>> BEGIN {원본}" / "-- <<< END {원본}" 마커로 감싼 원문이어야 하고, DROP 블록에 advisor 테이블 전부가 있어야 하며,
 * advisor 블록은 stock 블록 뒤에 와야 한다(FK 는 없지만 뷰·시드 순서를 명확히 하기 위해).
 */
class AdvisorSchemaSyncTest {

  private static final String MIRROR = "schema-postgres.sql";
  private static final List<String> SOURCES = List.of("db/advisor-schema.sql", "db/advisor-seed.sql");
  private static final String LAST_STOCK_SOURCE = "db/stock-seed.sql";
  private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE IF NOT EXISTS (\\w+)");
  static final int TABLE_COUNT = 14;

  @Test
  @DisplayName("마커 사이 내용이 원본 두 파일과 줄 단위로 같다")
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
  @DisplayName("advisor 블록은 stock 블록 뒤에, schema → seed 순서다")
  void mirrorBlocksAreInDependencyOrder() throws IOException {
    List<String> mirror = lines(MIRROR);
    int previous = indexOfOnly(mirror, "-- <<< END " + LAST_STOCK_SOURCE);
    assertThat(previous).as("stock seed END 마커").isGreaterThanOrEqualTo(0);
    for (String source : SOURCES) {
      int begin = indexOfOnly(mirror, "-- >>> BEGIN " + source);
      assertThat(begin).as("%s 는 앞 블록 뒤에 와야 한다", source).isGreaterThan(previous);
      previous = begin;
    }
  }

  @Test
  @DisplayName("DROP 블록이 advisor 테이블 전부를 마커 앞에서 CASCADE 로 지운다")
  void dropBlockCoversEveryAdvisorTable() throws IOException {
    List<String> tables = new ArrayList<>();
    for (String line : lines("db/advisor-schema.sql")) {
      Matcher m = CREATE_TABLE.matcher(line);
      if (m.find()) {
        tables.add(m.group(1));
      }
    }
    assertThat(tables).hasSize(TABLE_COUNT).allMatch(t -> t.startsWith("tb_advisor_"));

    List<String> mirror = lines(MIRROR);
    int firstBegin = indexOfOnly(mirror, "-- >>> BEGIN " + SOURCES.getFirst());
    List<String> head = mirror.subList(0, firstBegin);
    for (String table : tables) {
      assertThat(head)
          .as("%s 의 DROP 이 마커 앞 DROP 블록에 없다", table)
          .contains("DROP TABLE IF EXISTS " + table + " CASCADE;");
    }
  }

  private static List<String> lines(String resource) throws IOException {
    try (InputStream in = AdvisorSchemaSyncTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("리소스 %s 가 없다", resource).isNotNull();
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
      return stripTrailingBlank(List.of(text.split("\n", -1)));
    }
  }

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

package kr.hvy.blog.modules.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 룩어헤드 불변식(운영 문서 §8, note-v1 2026-09-21): 12:00 관측값은 tb_advisor_pick_note·tb_advisor_intraday_check 에만 살고, 특징·IC·채점·교훈·스크리닝·시장 특징 SQL 은
 * 이 두 테이블을 읽지 않는다. 소스 텍스트에 테이블명이 없음을 단언한다 — 새 조인이 슬며시 들어오면 여기서 막힌다.
 */
class AdvisorSqlBoundaryTest {

  private static final Path BASE = Path.of("src/main/java/kr/hvy/blog/modules/advisor/application/service");
  private static final List<String> SQL_OWNERS = List.of("FeatureSql.java", "SignalIcService.java", "AdviceScoringService.java", "LessonService.java",
      "CandidateScreeningService.java", "MarketFeatureService.java");
  private static final List<String> INTRADAY_TABLES = List.of("tb_advisor_pick_note", "tb_advisor_intraday_check");

  @Test
  @DisplayName("특징·IC·채점·교훈·스크리닝·시장 특징 SQL 소유 클래스는 12:00 테이블을 참조하지 않는다")
  void intradayTablesNeverFeedLearningSql() throws IOException {
    for (String file : SQL_OWNERS) {
      Path path = BASE.resolve(file);
      assertThat(path).as("SQL 소유 클래스 %s 가 있어야 한다(이동했으면 목록을 갱신)", file).exists();
      String source = Files.readString(path, StandardCharsets.UTF_8);
      for (String table : INTRADAY_TABLES) {
        assertThat(source).as("%s 가 %s 를 참조한다 — 12:00 정보가 특징·IC·채점·교훈으로 흘러들면 룩어헤드", file, table).doesNotContain(table);
      }
    }
  }
}

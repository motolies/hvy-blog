package kr.hvy.blog.modules.stock.client.gdelt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kr.hvy.blog.modules.stock.client.GdeltProperties;
import kr.hvy.blog.modules.stock.domain.code.TimelineMode;
import kr.hvy.blog.modules.stock.domain.model.EventTheme;
import kr.hvy.blog.modules.stock.domain.model.EventTimelinePoint;
import kr.hvy.blog.modules.stock.domain.model.FeedArticle;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * GDELT DOC 2.0 실측 (네트워크 필요, 키 불필요). 2026-09-20 첫 실측에서 timelinetone 은 7일 창에 <b>시간 단위</b> 버킷으로 왔고,
 * 연속 호출은 429("one every 5 seconds") 를 냈으며 60초 간격에서도 429 가 섞였다. 확인할 것: ① 30일 창의 버킷 해상도(일/시간) ② volraw 의 norm 필드
 * ③ artlist 필드명·건수(빈 응답 {} 이면 창을 넓혀 본다) ④ 429 빈도 — 어댑터 스로틀(6초)·백오프(30초×3)로 충분한지.
 * <pre>
 * GDELT_PROBE=true ./gradlew test --tests "kr.hvy.blog.modules.stock.client.gdelt.GdeltDocManualTest" -i
 * </pre>
 */
class GdeltDocManualTest {

  @Test
  @DisplayName("한 테마의 3모드 응답 형태·해상도·건수를 출력한다")
  void probe() {
    Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("GDELT_PROBE")), "GDELT_PROBE=true 일 때만 실행");
    GdeltProperties properties = new GdeltProperties();
    GdeltDocAdapter adapter = new GdeltDocAdapter(RestClient.create(), properties);
    EventTheme theme = EventTheme.parse("KR_GEO:한반도·북한:(\"North Korea\" OR \"Korean Peninsula\") sourcelang:english");
    LocalDate to = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    LocalDate from = to.minusDays(29);

    SourceFetch<EventTimelinePoint> volume = adapter.fetchTimeline(theme, TimelineMode.VOLUME, from, to);
    print("volraw", volume.rows());
    SourceFetch<EventTimelinePoint> tone = adapter.fetchTimeline(theme, TimelineMode.TONE, from, to);
    print("tone", tone.rows());
    Instant now = Instant.now();
    SourceFetch<FeedArticle> articles = adapter.fetchArticles(theme, now.minus(Duration.ofHours(36)), now, 10);
    System.out.printf("[gdelt] artlist rows=%d%n", articles.rows().size());
    articles.rows().stream().limit(3).forEach(a -> System.out.printf("  %s | %s | %s | %s%n", a.seenDate(), a.domain(), a.language(), a.title()));

    assertThat(volume.rows()).as("30일 창 기사량 시계열").isNotEmpty();
    assertThat(volume.rows().getFirst().norm()).as("volraw 는 norm(전체 모니터 건수)을 줘야 비율을 만들 수 있다").isNotNull();
  }

  private static void print(String label, List<EventTimelinePoint> points) {
    if (points.isEmpty()) {
      System.out.printf("[gdelt] %s rows=0%n", label);
      return;
    }
    Duration step = points.size() > 1 ? Duration.between(points.get(0).at(), points.get(1).at()) : Duration.ZERO;
    System.out.printf("[gdelt] %s rows=%d first=%s last=%s bucket=%s value0=%s norm0=%s%n", label, points.size(), points.getFirst().at(),
        points.getLast().at(), step, points.getFirst().value(), points.getFirst().norm());
  }
}

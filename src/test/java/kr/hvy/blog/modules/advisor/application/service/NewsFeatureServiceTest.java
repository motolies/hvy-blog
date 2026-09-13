package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.NewsBlock;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 헤드라인 선별 규칙: 시장·후보별 상한, 전체 상한(후보별 오래된 것부터 제거), 같은 기사 = 같은 id, 후보 밖 종목 기사 제외, 제목 정리.
 */
class NewsFeatureServiceTest {

  private final AdvisorProperties.News cfg = new AdvisorProperties.News();
  private final Instant asOf = Instant.parse("2026-09-11T10:30:00Z");

  @Test
  @DisplayName("시장 ≤12·후보별 ≤3 로 고르고 후보 밖 종목만 태깅된 기사는 버린다. 같은 기사가 두 종목에 붙으면 같은 id")
  void selectsWithinLimits() {
    List<NewsItem> rows = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      rows.add(item(100 + i, asOf.minusSeconds(60L * i), "시장 " + i, List.of()));
    }
    for (int j = 0; j < 5; j++) {
      rows.add(item(200 + j, asOf.minusSeconds(3600L + 60L * j), "삼성 " + j, List.of("005930")));
    }
    rows.add(item(300, asOf.minusSeconds(7200), "공동 기사 <!channel>", List.of("005930", "000660")));
    rows.add(item(400, asOf.minusSeconds(7300), "후보 밖", List.of("999999")));
    rows.sort((a, b) -> b.publishedAt().compareTo(a.publishedAt()));

    NewsBlock block = NewsFeatureService.select(rows, List.of("005930", "000660"), asOf, cfg);

    assertThat(block).isNotNull();
    assertThat(block.market()).hasSize(12);
    assertThat(block.market().getFirst().id()).isEqualTo("N1");
    assertThat(block.byTicker()).containsOnlyKeys("005930", "000660");
    assertThat(block.byTicker().get("005930")).hasSize(3).extracting(NewsBlock.Headline::title).containsExactly("삼성 0", "삼성 1", "삼성 2");
    assertThat(block.byTicker().get("000660")).hasSize(1);
    assertThat(block.byTicker().get("000660").getFirst().title()).as("멘션 제거").isEqualTo("공동 기사");
    assertThat(block.ids()).doesNotContain("후보 밖");
    assertThat(block.tickersById().get(block.byTicker().get("000660").getFirst().id())).containsExactlyInAnyOrder("000660");
    assertThat(block.tickersById().get("N1")).isEmpty();
    assertThat(block.size()).isEqualTo(16);
  }

  @Test
  @DisplayName("전체 상한을 넘으면 후보별 목록의 오래된 것부터 뺀다. 비면 null")
  void totalLimit() {
    cfg.setTotalLimit(14);
    List<NewsItem> rows = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      rows.add(item(100 + i, asOf.minusSeconds(60L * i), "시장 " + i, List.of()));
    }
    for (int j = 0; j < 3; j++) {
      rows.add(item(200 + j, asOf.minusSeconds(3600L + 60L * j), "A " + j, List.of("A")));
      rows.add(item(300 + j, asOf.minusSeconds(3000L + 60L * j), "B " + j, List.of("B")));
    }
    rows.sort((a, b) -> b.publishedAt().compareTo(a.publishedAt()));

    NewsBlock block = NewsFeatureService.select(rows, List.of("A", "B"), asOf, cfg);
    assertThat(block.size()).isEqualTo(14);
    assertThat(block.market()).hasSize(12);
    int a = block.byTicker().getOrDefault("A", List.of()).size();
    int b = block.byTicker().getOrDefault("B", List.of()).size();
    assertThat(a + b).isEqualTo(2);
    assertThat(b).as("A 가 더 오래돼 먼저 줄어든다").isGreaterThanOrEqualTo(a);

    assertThat(NewsFeatureService.select(List.of(), List.of("A"), asOf, cfg)).isNull();
    assertThat(NewsFeatureService.select(List.of(item(1, asOf, "x", List.of("Z"))), List.of("A"), asOf, cfg)).as("후보 밖만 있으면 null").isNull();
  }

  @Test
  @DisplayName("제목 정리: 멘션·제어문자·연속 공백 제거, 길이 절단")
  void sanitize() {
    assertThat(NewsFeatureService.sanitize("  삼성전자,\t<!here> 실적\n발표  ", 120)).isEqualTo("삼성전자, 실적 발표");
    assertThat(NewsFeatureService.sanitize("가나다라마바사", 5)).isEqualTo("가나다라…");
    assertThat(NewsFeatureService.sanitize(null, 5)).isEmpty();
  }

  private static NewsItem item(long id, Instant at, String title, List<String> tickers) {
    return NewsItem.builder().newsId(id).source("KIS").publishedAt(at).title(title).titleHash(NewsItem.hashTitle(title)).tickers(tickers).build();
  }
}

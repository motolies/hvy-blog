package kr.hvy.blog.modules.stock.client.gdelt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import kr.hvy.blog.modules.stock.client.KisJson;
import kr.hvy.blog.modules.stock.client.gdelt.dto.GdeltArticleListResponse;
import kr.hvy.blog.modules.stock.client.gdelt.dto.GdeltTimelineResponse;
import kr.hvy.blog.modules.stock.domain.model.EventTimelinePoint;
import kr.hvy.blog.modules.stock.domain.model.FeedArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GDELT DOC 2.0 응답 파싱 (2026-09-20 실측 tone 응답 형식 + 문서 형식의 volraw·artlist). 알 수 없는 필드(query_details·url_mobile 등)는 무시된다.
 */
class GdeltDocAdapterTest {

  @Test
  @DisplayName("timelinetone: 시간 버킷·값, norm 없음")
  void tonePoints() {
    String body = """
        {"query_details": {"title": "(\\"North Korea\\") sourcelang:english", "date_resolution": "hour"},
         "timeline": [ { "series": "Average Tone", "data": [ { "date": "20260912T000000Z", "value": 0 },{ "date": "20260912T010000Z", "value": -1.6935 } ] } ]}
        """;
    List<EventTimelinePoint> points = GdeltDocAdapter.toPoints(KisJson.read(body, GdeltTimelineResponse.class));
    assertThat(points).hasSize(2);
    assertThat(points.get(1).at()).isEqualTo(Instant.parse("2026-09-12T01:00:00Z"));
    assertThat(points.get(1).value()).isEqualTo(-1.6935);
    assertThat(points.get(1).norm()).isNull();
  }

  @Test
  @DisplayName("timelinevolraw: value 와 norm(전체 모니터 기사 수), 날짜 불량 점은 버린다")
  void volumePoints() {
    String body = """
        {"timeline": [ { "series": "Article Count", "data": [ { "date": "20260912T000000Z", "value": 12, "norm": 34567 },
                                                                { "date": "bad", "value": 1, "norm": 2 } ] } ]}
        """;
    List<EventTimelinePoint> points = GdeltDocAdapter.toPoints(KisJson.read(body, GdeltTimelineResponse.class));
    assertThat(points).hasSize(1);
    assertThat(points.getFirst().value()).isEqualTo(12.0);
    assertThat(points.getFirst().norm()).isEqualTo(34567L);
    assertThat(GdeltDocAdapter.toPoints(KisJson.read("{}", GdeltTimelineResponse.class))).isEmpty();
  }

  @Test
  @DisplayName("artlist: 기사 필드, 빈 응답 {} 은 빈 목록, 제목·URL·시각 중 하나라도 없으면 버린다")
  void articles() {
    String body = """
        {"articles": [
          {"url": "https://ex.com/a", "url_mobile": "", "title": " North Korea fires missile ", "seendate": "20260918T233000Z",
           "socialimage": "", "domain": "ex.com", "language": "English", "sourcecountry": "United States"},
          {"url": "https://ex.com/b", "title": "", "seendate": "20260918T230000Z", "domain": "ex.com", "language": "English"},
          {"url": "https://ex.com/c", "title": "no date", "seendate": "", "domain": "ex.com", "language": "English"}
        ]}
        """;
    List<FeedArticle> articles = GdeltDocAdapter.toArticles(KisJson.read(body, GdeltArticleListResponse.class));
    assertThat(articles).hasSize(1);
    FeedArticle a = articles.getFirst();
    assertThat(a.title()).isEqualTo("North Korea fires missile");
    assertThat(a.seenDate()).isEqualTo(Instant.parse("2026-09-18T23:30:00Z"));
    assertThat(a.domain()).isEqualTo("ex.com");
    assertThat(GdeltDocAdapter.toArticles(KisJson.read("{}", GdeltArticleListResponse.class))).isEmpty();
  }

  @Test
  @DisplayName("쿼리 인코딩: 공백은 %20, 따옴표·괄호는 퍼센트 인코딩")
  void encode() {
    assertThat(GdeltDocAdapter.encode("(\"North Korea\" OR 북한) sourcelang:english")).doesNotContain(" ").doesNotContain("+")
        .contains("%22North%20Korea%22").contains("sourcelang%3Aenglish");
    assertThat(GdeltDocAdapter.seen("20260918T233000Z")).isEqualTo(Instant.parse("2026-09-18T23:30:00Z"));
    assertThat(GdeltDocAdapter.seen("2026-09-18")).isNull();
  }
}

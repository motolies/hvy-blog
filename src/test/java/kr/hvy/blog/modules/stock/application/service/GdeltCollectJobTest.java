package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.EventFeedPort;
import kr.hvy.blog.modules.stock.client.GdeltProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TimelineMode;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.EventTheme;
import kr.hvy.blog.modules.stock.domain.model.EventTimelinePoint;
import kr.hvy.blog.modules.stock.domain.model.EventTimelineRow;
import kr.hvy.blog.modules.stock.domain.model.FeedArticle;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import kr.hvy.blog.modules.stock.repository.jdbc.EventTimelineWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 사건 피드 수집: 시간 버킷 → 완결 UTC 일자 집계(기사 수 가중 톤·비율·오늘 제외), 라이브(헤드라인 포함)·백필(청크·헤드라인 없음), 기사 → 뉴스 행.
 */
class GdeltCollectJobTest {

  private static final EventTheme THEME = EventTheme.parse("KR_GEO:한반도:(\"North Korea\") sourcelang:english");

  private final EventFeedPort port = mock(EventFeedPort.class);
  private final EventTimelineWriter timelineWriter = mock(EventTimelineWriter.class);
  private final StockNewsWriter newsWriter = mock(StockNewsWriter.class);
  private final GdeltProperties properties = new GdeltProperties();
  private final GdeltCollectJob job = new GdeltCollectJob(port, timelineWriter, newsWriter, properties);

  GdeltCollectJobTest() {
    properties.setThemes(List.of("KR_GEO:한반도:(\"North Korea\") sourcelang:english"));
    properties.setTimelineDays(3);
    properties.setBackfillChunkDays(90);
    properties.setMaxRecords(5);
    properties.setWindowHours(36);
  }

  @Test
  @DisplayName("집계: 시간 버킷을 UTC 일자로 묶고 톤은 기사 수 가중, 짝 없는 톤은 단순 평균, 오늘(UTC)은 버린다")
  void aggregate() {
    LocalDate today = LocalDate.of(2026, 9, 20);
    Instant d1a = Instant.parse("2026-09-18T00:00:00Z");
    Instant d1b = Instant.parse("2026-09-18T12:00:00Z");
    Instant d2 = Instant.parse("2026-09-19T05:00:00Z");
    Instant todayBucket = Instant.parse("2026-09-20T01:00:00Z");
    List<EventTimelinePoint> volume = List.of(new EventTimelinePoint(d1a, 10, 1000L), new EventTimelinePoint(d1b, 30, 3000L),
        new EventTimelinePoint(d2, 5, 500L), new EventTimelinePoint(todayBucket, 99, 9L));
    List<EventTimelinePoint> tone = List.of(new EventTimelinePoint(d1a, -1.0, null), new EventTimelinePoint(d1b, -3.0, null),
        new EventTimelinePoint(Instant.parse("2026-09-19T06:00:00Z"), -2.0, null), new EventTimelinePoint(todayBucket, -9.0, null));

    List<EventTimelineRow> rows = GdeltCollectJob.aggregate("KR_GEO", volume, tone, today, EventTimelineRow.SOURCE_LIVE);

    assertThat(rows).extracting(EventTimelineRow::obsDate).containsExactly(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 19));
    EventTimelineRow first = rows.getFirst();
    assertThat(first.articleVol()).isEqualTo(40L);
    assertThat(first.totalVol()).isEqualTo(4000L);
    assertThat(first.volRatio()).isCloseTo(0.01, within(1e-9));
    assertThat(first.avgTone()).as("(−1×10 + −3×30)/40").isCloseTo(-2.5, within(1e-9));
    assertThat(first.source()).isEqualTo("LIVE");
    EventTimelineRow second = rows.get(1);
    assertThat(second.avgTone()).as("톤 버킷(06:00)에 짝 볼륨(05:00)이 없으면 단순 평균").isCloseTo(-2.0, within(1e-9));
    assertThat(second.volRatio()).isCloseTo(0.01, within(1e-9));
  }

  @Test
  @DisplayName("라이브: 최근 timeline-days 시계열 + window-hours 헤드라인, source=LIVE, provider_code=테마, tickers 빈 배열")
  void live() {
    LocalDate todayUtc = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
    Instant yesterday = todayUtc.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    when(port.fetchTimeline(eq(THEME), eq(TimelineMode.VOLUME), any(), any()))
        .thenReturn(new SourceFetch<>(List.of(new EventTimelinePoint(yesterday, 4, 200L)), 1));
    when(port.fetchTimeline(eq(THEME), eq(TimelineMode.TONE), any(), any()))
        .thenReturn(new SourceFetch<>(List.of(new EventTimelinePoint(yesterday, -1.5, null)), 1));
    when(port.fetchArticles(eq(THEME), any(), any(), anyInt())).thenReturn(new SourceFetch<>(List.of(
        new FeedArticle("https://ex.com/a", "North Korea fires missile", Instant.now().minusSeconds(600), "ex.com", "English")), 1));
    when(timelineWriter.upsert(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    when(newsWriter.upsert(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    CollectExecution execution = execution(BackfillRequest.empty());
    job.execute(execution);

    verify(port).fetchTimeline(THEME, TimelineMode.VOLUME, todayUtc.minusDays(3), todayUtc.minusDays(1));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EventTimelineRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(timelineWriter).upsert(rows.capture());
    assertThat(rows.getValue()).singleElement().satisfies(r -> {
      assertThat(r.source()).isEqualTo(EventTimelineRow.SOURCE_LIVE);
      assertThat(r.obsDate()).isEqualTo(todayUtc.minusDays(1));
      assertThat(r.volRatio()).isCloseTo(0.02, within(1e-9));
    });
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<NewsItem>> items = ArgumentCaptor.forClass(List.class);
    verify(newsWriter).upsert(items.capture());
    NewsItem item = items.getValue().getFirst();
    assertThat(item.source()).isEqualTo("GDELT");
    assertThat(item.providerCode()).isEqualTo("KR_GEO");
    assertThat(item.serialNo()).hasSize(40);
    assertThat(item.tickers()).isEmpty();
    assertThat(item.origin()).isEqualTo("ex.com");
    Map<String, Object> meta = execution.metadataSnapshot();
    assertThat(meta).containsEntry("mode", "LIVE").containsEntry("articlesInserted", 1).containsEntry("targets", 1);
    assertThat(execution.totalRows()).isEqualTo(2);
    assertThat(job.jobType()).isEqualTo(CollectJobType.NEWS);
  }

  @Test
  @DisplayName("백필: startDate~endDate 를 청크로 나눠 시계열만 받고(source=BACKFILL) 헤드라인은 받지 않는다")
  void backfill() {
    when(port.fetchTimeline(any(), any(), any(), any())).thenReturn(SourceFetch.empty());
    CollectExecution execution = execution(new BackfillRequest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), null, null, null, null, null, null));
    job.execute(execution);

    // 1/1~3/31 이 정확히 90일이라 청크 2개: [1/1, 3/31], [4/1, 4/1]
    verify(port, times(2)).fetchTimeline(eq(THEME), eq(TimelineMode.VOLUME), any(), any());
    verify(port).fetchTimeline(THEME, TimelineMode.VOLUME, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
    verify(port).fetchTimeline(THEME, TimelineMode.VOLUME, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1));
    verify(port, never()).fetchArticles(any(), any(), any(), anyInt());
    verify(newsWriter, never()).upsert(any());
    assertThat(execution.metadataSnapshot()).containsEntry("mode", "BACKFILL");
  }

  @Test
  @DisplayName("청크: 90일 단위 양끝 포함, from > to 는 빈 목록")
  void chunks() {
    List<LocalDate[]> c = GdeltCollectJob.chunks(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 90);
    assertThat(c).hasSize(1);
    assertThat(c.getFirst()[1]).isEqualTo(LocalDate.of(2026, 3, 31));
    List<LocalDate[]> two = GdeltCollectJob.chunks(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), 90);
    assertThat(two).hasSize(2);
    assertThat(two.get(1)[0]).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(two.get(1)[1]).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(GdeltCollectJob.chunks(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), 90)).isEmpty();
  }

  @Test
  @DisplayName("기사 → 뉴스 행: 제어문자 정리·절단, 미래 시각·빈 제목 거부, URL 해시 40자")
  void toNewsItem() {
    Instant now = Instant.parse("2026-09-19T10:30:00Z");
    NewsItem item = GdeltCollectJob.toNewsItem(THEME, new FeedArticle("https://ex.com/a", " North\tKorea  fires ", now.minusSeconds(60),
        "a-very-long-domain-name-that-exceeds-sixty-characters-for-sure.example.com", "English"), now).orElseThrow();
    assertThat(item.title()).isEqualTo("North Korea fires");
    assertThat(item.origin()).hasSize(60);
    assertThat(item.publishedAt()).isEqualTo(now.minusSeconds(60));
    assertThat(item.serialNo()).isEqualTo(GdeltCollectJob.urlHash("https://ex.com/a")).hasSize(40);
    assertThat(GdeltCollectJob.toNewsItem(THEME, new FeedArticle("u", "future", now.plusSeconds(600), "d", "l"), now)).isEmpty();
    assertThat(GdeltCollectJob.toNewsItem(THEME, new FeedArticle("u", "   ", now, "d", "l"), now)).isEmpty();
  }

  private static CollectExecution execution(BackfillRequest request) {
    return new CollectExecution(StockCollectRun.builder().runId(9L).jobType(CollectJobType.NEWS).triggerType(TriggerType.API).build(),
        request, mock(CollectRunService.class));
  }
}

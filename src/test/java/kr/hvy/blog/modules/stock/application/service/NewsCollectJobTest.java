package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisNewsTitleResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 뉴스 제목 수집: 응답 행 → 저장 행 변환(작성 시각 KST·종목코드 6자리·제목 정리·미래 시각 거부), 증분 하한(마지막 작성 시각 − 1h), 메타데이터.
 */
class NewsCollectJobTest {

  private final KisMarketDataPort port = mock(KisMarketDataPort.class);
  private final StockNewsWriter writer = mock(StockNewsWriter.class);
  private final KisProperties properties = new KisProperties();
  private final NewsCollectJob job = new NewsCollectJob(port, writer, properties);

  @Test
  @DisplayName("행 변환: data_dt+data_tm 은 KST, 종목코드는 6자리 숫자만, 제목 없음·미래 시각은 버린다")
  void toItem() {
    Instant now = Instant.parse("2026-09-11T10:30:00Z"); // 19:30 KST
    NewsItem item = NewsCollectJob.toItem(row("20260911", "161500", "삼성전자, HBM4 양산", "005930", "000660", "A12345", "", null), now).orElseThrow();
    assertThat(item.publishedAt()).isEqualTo(ZonedDateTime.parse("2026-09-11T16:15:00+09:00").toInstant());
    assertThat(item.tickers()).containsExactly("005930", "000660");
    assertThat(item.source()).isEqualTo("KIS");
    assertThat(item.titleHash()).hasSize(32);
    assertThat(NewsCollectJob.toItem(row("20260911", "93000", "짧은 시각", null, null, null, null, null), now).orElseThrow().publishedAt())
        .as("HHmmss 가 5자리면 앞에 0").isEqualTo(ZonedDateTime.parse("2026-09-11T09:30:00+09:00").toInstant());
    assertThat(NewsCollectJob.toItem(row("20260911", "161500", "  ", null, null, null, null, null), now)).isEmpty();
    assertThat(NewsCollectJob.toItem(row("20260912", "090000", "미래 기사", null, null, null, null, null), now)).as("시계 오차 5분 초과 미래").isEmpty();
    assertThat(NewsCollectJob.toItem(row("bad", "x", "형식 불량", null, null, null, null, null), now)).isEmpty();
    assertThat(NewsItem.hashTitle("삼성전자, HBM4 양산")).isEqualTo(NewsItem.hashTitle("삼성전자 HBM4  양산!"));
  }

  @Test
  @DisplayName("실행: 마지막 저장 작성 시각 − 1h 보다 오래된 행은 버리고 나머지를 넣으며 메타에 건수를 남긴다")
  void executeIncremental() {
    ZonedDateTime now = MarketClock.now();
    Instant latest = now.minusHours(3).toInstant();
    when(writer.latestPublishedAt("KIS")).thenReturn(latest);
    when(writer.upsert(any())).thenReturn(2);
    DateTimeFormatter d = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter t = DateTimeFormatter.ofPattern("HHmmss");
    ZonedDateTime fresh = now.minusMinutes(20);
    ZonedDateTime borderline = now.minusHours(3).minusMinutes(30); // latest − 30분 → 포함 (1h 완충)
    ZonedDateTime old = now.minusHours(5);
    when(port.fetchNewsTitles(any(), any(), isNull(), anyInt(), any())).thenReturn(List.of(
        row(fresh.format(d), fresh.format(t), "새 기사", "005930", null, null, null, null),
        row(borderline.format(d), borderline.format(t), "경계 기사", null, null, null, null, null),
        row(old.format(d), old.format(t), "옛 기사", null, null, null, null, null)));

    CollectExecution execution = new CollectExecution(StockCollectRun.builder().runId(5L).jobType(CollectJobType.NEWS).triggerType(TriggerType.API).build(),
        BackfillRequest.empty(), mock(CollectRunService.class));
    job.execute(execution);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<NewsItem>> saved = ArgumentCaptor.forClass(List.class);
    verify(writer).upsert(saved.capture());
    assertThat(saved.getValue()).extracting(NewsItem::title).containsExactly("새 기사", "경계 기사");
    assertThat(execution.totalRows()).isEqualTo(2);
    assertThat(execution.metadataSnapshot()).containsEntry("fetched", 3).containsEntry("candidates", 2).containsEntry("inserted", 2).containsEntry("skippedOld", 1);
    assertThat(job.jobType()).isEqualTo(CollectJobType.NEWS);
  }

  private static KisNewsTitleResponse.Row row(String date, String time, String title, String i1, String i2, String i3, String i4, String i5) {
    return new KisNewsTitleResponse.Row("SRN" + title.hashCode(), "2", date, time, title, "01", "연합", i1, i2, i3, i4, i5);
  }
}

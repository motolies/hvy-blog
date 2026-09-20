package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.MacroDataPort;
import kr.hvy.blog.modules.stock.client.MacroProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.MacroSeries;
import kr.hvy.blog.modules.stock.domain.code.MacroSource;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MacroObservation;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import kr.hvy.blog.modules.stock.repository.jdbc.MacroDailyWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 거시 지표 수집: 증분 창(최근 lookback-days)·백필(startDate)·시리즈별 격리·메타(latest·lagDays)·호출 수 집계.
 */
class MacroCollectJobTest {

  private final MacroDataPort port = mock(MacroDataPort.class);
  private final MacroDailyWriter writer = mock(MacroDailyWriter.class);
  private final MacroProperties properties = new MacroProperties();
  private final MacroCollectJob job = new MacroCollectJob(port, writer, properties);

  MacroCollectJobTest() {
    properties.setLookbackDays(10);
    properties.setSeries(List.of("VIX:CBOE:https://cboe/x.csv", "UST10Y:TREASURY:https://treasury/{year}.csv"));
  }

  @Test
  @DisplayName("증분: 오늘 − 9 ~ 오늘 창, 시리즈별 rows·latest·lagDays 메타, HTTP 호출 수를 run 통계에 더한다")
  void incremental() {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(9);
    when(port.fetchSeries(any(), eq(from), eq(today))).thenAnswer(inv -> {
      var spec = (kr.hvy.blog.modules.stock.domain.model.MacroSeriesSpec) inv.getArgument(0);
      MacroObservation obs = new MacroObservation(spec.series(), today.minusDays(1), new BigDecimal("17.71"), spec.source(), today);
      return new SourceFetch<>(List.of(obs), spec.source() == MacroSource.TREASURY ? 2 : 1);
    });
    when(writer.upsert(any())).thenReturn(1);

    CollectExecution execution = execution(BackfillRequest.empty());
    job.execute(execution);

    assertThat(job.jobType()).isEqualTo(CollectJobType.MACRO);
    assertThat(execution.totalRows()).isEqualTo(2);
    assertThat(execution.processedCount()).isEqualTo(2);
    assertThat(execution.failureCount()).isZero();
    Map<String, Object> meta = execution.metadataSnapshot();
    assertThat(meta).containsEntry("mode", "INCREMENTAL").containsEntry("from", from.toString()).containsEntry("targets", 2);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> series = (Map<String, Map<String, Object>>) meta.get("series");
    assertThat(series.get("VIX")).containsEntry("rows", 1).containsEntry("latest", today.minusDays(1).toString()).containsEntry("lagDays", 1L);
    assertThat(series.get("UST10Y")).containsEntry("fetched", 1);
  }

  @Test
  @DisplayName("백필: startDate 부터, 한 시리즈가 실패해도 다른 시리즈는 진행하고 실패는 기록된다")
  void backfillIsolatesFailure() {
    LocalDate start = LocalDate.of(2015, 1, 1);
    when(port.fetchSeries(argThat(s -> s != null && s.series() == MacroSeries.VIX), eq(start), any()))
        .thenThrow(new IllegalStateException("CBOE 503"));
    when(port.fetchSeries(argThat(s -> s != null && s.series() == MacroSeries.UST10Y), eq(start), any()))
        .thenReturn(new SourceFetch<>(List.of(new MacroObservation(MacroSeries.UST10Y, start, new BigDecimal("2.1"), MacroSource.TREASURY,
            start.plusDays(1))), 12));
    when(writer.upsert(any())).thenReturn(1);

    CollectExecution execution = execution(new BackfillRequest(start, null, null, null, null, null, null, null));
    job.execute(execution);

    verify(writer).upsert(argThat(rows -> rows.size() == 1 && rows.getFirst().series() == MacroSeries.UST10Y));
    assertThat(execution.failureCount()).isEqualTo(1);
    assertThat(execution.failures().getFirst().target()).isEqualTo("VIX");
    assertThat(execution.processedCount()).isEqualTo(1);
    assertThat(execution.metadataSnapshot()).containsEntry("mode", "BACKFILL").containsEntry("from", start.toString());
  }

  private static CollectExecution execution(BackfillRequest request) {
    return new CollectExecution(StockCollectRun.builder().runId(7L).jobType(CollectJobType.MACRO).triggerType(TriggerType.API).build(),
        request, mock(CollectRunService.class));
  }
}

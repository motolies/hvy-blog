package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.EtfNavRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.StockEtfNavWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ETF NAV 수집: 대상은 증권그룹 EF 로만 해석하고, 증분은 종목당 최근 1윈도우 1호출이다.
 */
class StockEtfNavCollectServiceTest {

  private final KisMarketDataPort port = mock(KisMarketDataPort.class);
  private final StockEtfNavWriter writer = mock(StockEtfNavWriter.class);
  private final TargetResolver targetResolver = mock(TargetResolver.class);
  private final CollectCheckpointService checkpointService = mock(CollectCheckpointService.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  private final KisProperties properties = new KisProperties();
  private final StockEtfNavCollectService service = new StockEtfNavCollectService(port, writer, targetResolver,
      new ConcurrentTargetRunner(properties), checkpointService, mock(DateWindowPaginator.class), properties);

  @Test
  @DisplayName("increment: ETF 마다 [오늘-(window-1), 오늘] 1호출 후 행을 upsert 하고 카운터를 올린다")
  @SuppressWarnings("unchecked")
  void collectRecentFetchesOneWindowPerTicker() {
    LocalDate today = MarketClock.today();
    when(port.fetchEtfNavDaily(eq("069500"), any(), any(), any())).thenReturn(List.of(
        row("20260904", "45000", "45100.1234", "-0.22"),
        row("20260903", "44800", "44790", "0.02")));
    when(writer.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    CollectExecution exec = execution();

    service.collectRecent(exec, List.of("069500"));

    verify(port).fetchEtfNavDaily(eq("069500"), eq(today.minusDays(properties.getBackfill().getWindowDays() - 1L)),
        eq(today), any());
    ArgumentCaptor<List<EtfNavRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(writer).upsert(rows.capture());
    assertThat(rows.getValue()).hasSize(2);
    assertThat(rows.getValue().get(0).nav()).isEqualByComparingTo("45100.1234");
    assertThat(exec.totalRows()).isEqualTo(2);
    assertThat(exec.processedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("backfill: 대상은 증권그룹 EF 로 해석하고 ETF_NAV_BACKFILL 체크포인트를 준비한다. 대상이 없으면 예외")
  void executeResolvesEtfTargets() {
    when(targetResolver.resolveTickers(any(), eq(StockEtfNavCollectService.ETF_GROUPS))).thenReturn(List.of("069500", "102110"));
    when(checkpointService.findResumable(eq(CollectJobType.ETF_NAV_BACKFILL), anyInt())).thenReturn(List.of());
    CollectExecution exec = execution();

    service.execute(exec);

    verify(checkpointService).initialize(eq(CollectJobType.ETF_NAV_BACKFILL), eq(List.of("069500", "102110")),
        eq(MarketClock.today()), eq(false));
    assertThat(exec.metadataSnapshot()).containsEntry("targets", 2).containsEntry("attempted", 0);

    when(targetResolver.resolveTickers(any(), eq(StockEtfNavCollectService.ETF_GROUPS))).thenReturn(List.of());
    assertThatThrownBy(() -> service.execute(execution())).isInstanceOf(IllegalStateException.class);
  }

  private CollectExecution execution() {
    StockCollectRun run = StockCollectRun.builder().runId(3L).jobType(CollectJobType.ETF_NAV_BACKFILL)
        .triggerType(TriggerType.API).build();
    return new CollectExecution(run, BackfillRequest.empty(), runService);
  }

  private static KisEtfNavResponse.Row row(String date, String close, String nav, String disparity) {
    return new KisEtfNavResponse.Row(date, close, "100", "2", "0.22", "1234567", "100", disparity, "-100.12", nav, "2", "50.5", "0.11");
  }
}

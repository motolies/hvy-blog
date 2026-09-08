package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FullBackfillJobTest {

  private final StockCollectOrchestrator orchestrator = mock(StockCollectOrchestrator.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<StockCollectOrchestrator> provider = mock(ObjectProvider.class);
  private final FullBackfillJob job = new FullBackfillJob(provider);

  private CollectExecution parent() {
    StockCollectRun run = StockCollectRun.builder().runId(100L).jobType(CollectJobType.BACKFILL_ALL).triggerType(TriggerType.API).build();
    return new CollectExecution(run, BackfillRequest.forTickers(List.of("005930")), runService);
  }

  private static StockCollectRun finished(long id, CollectJobType type, CollectStatus status, long rows) {
    StockCollectRun run = StockCollectRun.builder().runId(id).jobType(type).triggerType(TriggerType.SCHEDULER)
        .status(status).rowsUpserted(rows).build();
    return run;
  }

  @Test
  @DisplayName("ORDER 의 전 단계를 순서대로 상위 runId 를 붙여 동기 실행하고, 실패 단계는 기록한 뒤 계속 간다")
  @SuppressWarnings("unchecked")
  void runsAllStepsInOrder() {
    when(provider.getObject()).thenReturn(orchestrator);
    List<CollectJobType> called = new ArrayList<>();
    when(orchestrator.trigger(any(), any(), eq(TriggerType.SCHEDULER), eq(100L))).thenAnswer(inv -> {
      CollectJobType type = inv.getArgument(0);
      called.add(type);
      if (type == CollectJobType.HOLIDAY) {
        BackfillRequest req = inv.getArgument(1);
        assertThat(req.startDate()).isNotNull(); // 휴장일은 오늘부터
        assertThat(req.tickers()).isNull();
      } else {
        assertThat(((BackfillRequest) inv.getArgument(1)).tickers()).containsExactly("005930");
      }
      if (type == CollectJobType.INVESTOR_BACKFILL) {
        throw new CollectAlreadyRunningException(type, 55L);
      }
      CollectStatus status = type == CollectJobType.FINANCIAL_BACKFILL ? CollectStatus.FAILED : CollectStatus.SUCCESS;
      return new StockCollectOrchestrator.TriggerResult(finished(called.size(), type, status, 10), false);
    });
    CollectExecution exec = parent();

    job.execute(exec);

    List<CollectJobType> order = FullBackfillJob.ORDER;
    assertThat(called).containsExactlyElementsOf(order);
    assertThat(exec.processedCount()).isEqualTo(order.size() - 1); // 409 로 건너뛴 1단계 제외
    assertThat(exec.failureCount()).isEqualTo(2);                   // INVESTOR(409) + FINANCIAL(FAILED)
    assertThat(exec.totalRows()).isEqualTo((order.size() - 1) * 10L);
    List<Map<String, Object>> steps = (List<Map<String, Object>>) exec.metadataSnapshot().get("steps");
    assertThat(steps).hasSize(order.size());
    int investor = order.indexOf(CollectJobType.INVESTOR_BACKFILL);
    assertThat(steps.get(investor).get("status")).isEqualTo("SKIPPED_RUNNING");
    assertThat(steps.get(investor).get("runId")).isEqualTo(55L);
    assertThat(steps.get(order.indexOf(CollectJobType.FINANCIAL_BACKFILL)).get("status")).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("밸류에이션·시장통계 하위 요청은 날짜·지수·리셋을 떼고 종목 필터·force 만 넘긴다 (endDate 가 스냅샷 날짜로 오인되지 않게)")
  void snapshotSubRequestDropsDates() {
    BackfillRequest request = new BackfillRequest(LocalDate.of(2015, 1, 1), LocalDate.of(2026, 9, 4), "000000", "099999",
        List.of("005930"), List.of("0001"), true, true);

    for (CollectJobType type : List.of(CollectJobType.VALUATION, CollectJobType.MARKET_STAT)) {
      BackfillRequest sub = FullBackfillJob.subRequest(type, request);
      assertThat(sub.startDate()).isNull();
      assertThat(sub.endDate()).isNull();
      assertThat(sub.indexCodes()).isNull();
      assertThat(sub.resetCheckpoint()).isNull();
      assertThat(sub.tickers()).containsExactly("005930");
      assertThat(sub.tickerFrom()).isEqualTo("000000");
      assertThat(sub.force()).isTrue();
    }
    assertThat(FullBackfillJob.subRequest(CollectJobType.PRICE_BACKFILL, request)).isSameAs(request);
    assertThat(FullBackfillJob.ORDER).containsSubsequence(CollectJobType.INVESTOR_BACKFILL, CollectJobType.VALUATION,
        CollectJobType.MARKET_STAT, CollectJobType.FINANCIAL_BACKFILL);
  }

  @Test
  @DisplayName("상위 run 이 취소되면 남은 단계를 CANCELED 로 기록하고 실행하지 않는다")
  @SuppressWarnings("unchecked")
  void stopsAfterCancel() {
    when(provider.getObject()).thenReturn(orchestrator);
    when(orchestrator.trigger(any(), any(), eq(TriggerType.SCHEDULER), eq(100L))).thenAnswer(inv -> {
      CollectJobType type = inv.getArgument(0);
      if (type == CollectJobType.HOLIDAY) {
        when(runService.isCancelRequested(100L)).thenReturn(true); // 두 번째 단계 도중 취소
      }
      return new StockCollectOrchestrator.TriggerResult(finished(1, type, CollectStatus.SUCCESS, 1), false);
    });
    CollectExecution exec = parent();

    job.execute(exec);

    List<Map<String, Object>> steps = (List<Map<String, Object>>) exec.metadataSnapshot().get("steps");
    assertThat(steps.get(0).get("status")).isEqualTo("SUCCESS");
    assertThat(steps.get(1).get("status")).isEqualTo("SUCCESS");
    assertThat(steps.subList(2, FullBackfillJob.ORDER.size())).allSatisfy(s -> assertThat(s.get("status")).isEqualTo("CANCELED"));
  }
}

package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kr.hvy.blog.modules.stock.client.KisCallStats;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * flush 의 best-effort 규칙: 실패해도 던지지 않고 값을 보존해 다음 flush 에 누적 반영한다.
 */
class CollectExecutionTest {

  private final CollectRunService runService = mock(CollectRunService.class);
  private final StockCollectRun run = StockCollectRun.builder().runId(7L).jobType(CollectJobType.MASTER)
      .triggerType(TriggerType.API).build();
  private final CollectExecution execution = new CollectExecution(run, null, runService);

  /** run 전체가 공유하는 호출 통계 (KisApiClient 가 올리는 값을 직접 흉내낸다) */
  private KisCallStats stats() {
    return execution.context("005930").stats();
  }

  @Test
  @DisplayName("flush 실패 시 행 수와 호출 통계를 보존하고, 다음 flush 가 누적값으로 반영한다")
  void failedFlushRetainsAndRetries() {
    doThrow(new DataAccessResourceFailureException("db down"))
        .doNothing()
        .when(runService).addCounters(eq(7L), anyLong(), anyLong(), anyLong());
    execution.addRows(5);
    stats().getApiCalls().addAndGet(2);
    stats().getApiFails().incrementAndGet();

    assertThat(execution.flush()).isFalse();
    assertThat(execution.flushFailures()).isEqualTo(1);
    assertThat(execution.lastFlushError()).isInstanceOf(DataAccessResourceFailureException.class);

    execution.addRows(3);
    stats().getApiCalls().incrementAndGet();
    assertThat(execution.flush()).isTrue();

    InOrder order = inOrder(runService);
    order.verify(runService).addCounters(7L, 5L, 2L, 1L);
    order.verify(runService).addCounters(7L, 8L, 3L, 1L);
    assertThat(execution.totalRows()).isEqualTo(8); // totalRows 는 flush 와 무관하게 누적
  }

  @Test
  @DisplayName("반영할 값이 없으면 addCounters 를 부르지 않는다 (트랜잭션을 열지 않음)")
  void emptyFlushSkipsDb() {
    assertThat(execution.flush()).isTrue();
    verify(runService, never()).addCounters(anyLong(), anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("rateLimitHits 는 영속 대상이 아니라 flush 뒤에도 run 누적값이 유지된다")
  void rateLimitHitsSurviveFlush() {
    stats().getRateLimitHits().addAndGet(4);
    stats().getApiCalls().incrementAndGet();

    assertThat(execution.flush()).isTrue();

    assertThat(execution.rateLimitHits()).isEqualTo(4);
    assertThat(stats().getApiCalls().get()).isZero();
  }

  @Test
  @DisplayName("drain 과 restore 사이에 다른 flush 가 끼어들어도 반영 합계가 정확하다")
  void interleavedFlushKeepsTotals() {
    AtomicLong committed = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
      long rows = invocation.getArgument(1);
      if (calls.incrementAndGet() == 1) {
        // T1 이 5행을 떠 간 사이 T2 가 3행을 추가하고 flush 에 성공한 뒤, T1 의 UPDATE 가 실패하는 상황
        execution.addRows(3);
        assertThat(execution.flush()).isTrue();
        throw new DataAccessResourceFailureException("db down");
      }
      committed.addAndGet(rows);
      return null;
    }).when(runService).addCounters(eq(7L), anyLong(), anyLong(), anyLong());
    execution.addRows(5);

    assertThat(execution.flush()).isFalse(); // T1 실패 → 5 복원 (T2 의 3 을 덮지 않음)
    assertThat(execution.flush()).isTrue(); // 복원된 5 반영

    verify(runService).addCounters(7L, 3L, 0L, 0L);
    assertThat(committed.get()).isEqualTo(8);
    assertThat(execution.totalRows()).isEqualTo(8);
  }

  @Test
  @DisplayName("flush 실패가 있으면 metadata 에 횟수·원인·미반영 값을 남기고, 정상이면 키 자체가 없다")
  void metadataCarriesFlushFailures() {
    assertThat(execution.metadataSnapshot()).doesNotContainKeys("flushFailures", "unflushedRows");

    doThrow(new DataAccessResourceFailureException("db down"))
        .when(runService).addCounters(eq(7L), anyLong(), anyLong(), anyLong());
    execution.addRows(5);
    stats().getApiCalls().addAndGet(2);
    execution.flush();

    Map<String, Object> metadata = execution.metadataSnapshot();
    assertThat(metadata)
        .containsEntry("flushFailures", 1)
        .containsEntry("unflushedRows", 5L)
        .containsEntry("unflushedApiCalls", 2L)
        .containsEntry("unflushedApiFails", 0L);
    assertThat((String) metadata.get("lastFlushError")).contains("db down");
  }
}

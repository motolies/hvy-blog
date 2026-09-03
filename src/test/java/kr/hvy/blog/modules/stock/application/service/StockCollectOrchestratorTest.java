package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오케스트레이터의 run 생명주기 규칙 (Spring 없이 Mockito 로).
 */
class StockCollectOrchestratorTest {

  private final CollectRunService runService = mock(CollectRunService.class);
  private final KisProperties properties = new KisProperties();
  private final CollectNotifier notifier = new CollectNotifier(Optional.empty());
  private StockCollectRun run;

  @BeforeEach
  void setUp() {
    properties.setAppKey("key");
    properties.setAppSecret("secret");
    run = StockCollectRun.builder().runId(7L).jobType(CollectJobType.MASTER).triggerType(TriggerType.API).build();
    when(runService.start(any(), any(), any(), any(), any(), any())).thenReturn(run);
    when(runService.get(7L)).thenReturn(run);
  }

  private StockCollectOrchestrator orchestrator(CollectJob job, Executor executor) {
    return new StockCollectOrchestrator(List.of(job), runService, notifier, properties, executor);
  }

  private static CollectJob job(CollectJobType type, java.util.function.Consumer<CollectExecution> body) {
    return new CollectJob() {
      @Override
      public CollectJobType jobType() {
        return type;
      }

      @Override
      public void execute(CollectExecution execution) {
        body.accept(execution);
      }
    };
  }

  @Test
  @DisplayName("짧은 잡은 동기 실행되고 실패가 없으면 SUCCESS 로 종료된다")
  void syncJobSuccess() {
    CollectJob job = job(CollectJobType.MASTER, exec -> {
      exec.addRows(10);
      exec.targetDone();
    });

    StockCollectOrchestrator.TriggerResult result = orchestrator(job, Runnable::run)
        .trigger(CollectJobType.MASTER, null, TriggerType.API);

    assertThat(result.async()).isFalse();
    verify(runService).finish(eq(7L), eq(CollectStatus.SUCCESS), isNull());
    verify(runService).flushStats(eq(7L), eq(10L), any());
  }

  @Test
  @DisplayName("종목 단위 실패가 있으면 PARTIAL, 예외로 죽으면 FAILED 로 종료된다")
  void partialAndFailed() {
    CollectJob partial = job(CollectJobType.MASTER, exec -> {
      exec.targetDone();
      exec.recordFailure("005930", "EGW00201");
    });
    orchestrator(partial, Runnable::run).trigger(CollectJobType.MASTER, null, TriggerType.SCHEDULER);
    verify(runService).finish(eq(7L), eq(CollectStatus.PARTIAL), any(String.class));

    CollectJob failing = job(CollectJobType.MASTER, exec -> {
      throw new IllegalStateException("파일 손상");
    });
    orchestrator(failing, Runnable::run).trigger(CollectJobType.MASTER, null, TriggerType.SCHEDULER);
    verify(runService).finish(eq(7L), eq(CollectStatus.FAILED), eq("java.lang.IllegalStateException: 파일 손상"));
  }

  @Test
  @DisplayName("장시간 잡을 API 로 트리거하면 실행기에 제출하고 RUNNING 상태로 즉시 돌아온다")
  void longRunningIsAsync() {
    StockCollectRun backfill = StockCollectRun.builder().runId(8L).jobType(CollectJobType.PRICE_BACKFILL)
        .triggerType(TriggerType.API).build();
    when(runService.start(any(), any(), any(), any(), any(), any())).thenReturn(backfill);
    List<Runnable> submitted = new java.util.ArrayList<>();
    CollectJob job = job(CollectJobType.PRICE_BACKFILL, exec -> exec.targetDone());

    StockCollectOrchestrator.TriggerResult result = orchestrator(job, submitted::add)
        .trigger(CollectJobType.PRICE_BACKFILL, BackfillRequest.forTickers(List.of("005930")), TriggerType.API);

    assertThat(result.async()).isTrue();
    assertThat(result.run().getStatus()).isEqualTo(CollectStatus.RUNNING);
    assertThat(submitted).hasSize(1);
    verify(runService, never()).finish(anyLong(), any(), any());
  }

  @Test
  @DisplayName("실행기 큐가 가득 차면 run 을 FAILED 로 닫고 400 계열 예외를 던진다")
  void rejectedExecutionClosesRun() {
    Executor rejecting = r -> {
      throw new RejectedExecutionException("full");
    };
    CollectJob job = job(CollectJobType.PRICE_BACKFILL, exec -> {
    });
    StockCollectRun backfill = StockCollectRun.builder().runId(9L).jobType(CollectJobType.PRICE_BACKFILL)
        .triggerType(TriggerType.API).build();
    when(runService.start(any(), any(), any(), any(), any(), any())).thenReturn(backfill);

    assertThatThrownBy(() -> orchestrator(job, rejecting).trigger(CollectJobType.PRICE_BACKFILL, null, TriggerType.API))
        .isInstanceOf(CollectRequestException.class);
    verify(runService).finish(eq(9L), eq(CollectStatus.FAILED), any(String.class));
  }

  @Test
  @DisplayName("미등록 잡·형식 오류·앱키 미설정은 run 을 만들지 않고 거부한다")
  void rejectsBeforeCreatingRun() {
    CollectJob job = job(CollectJobType.MASTER, exec -> {
    });
    StockCollectOrchestrator orchestrator = orchestrator(job, Runnable::run);

    assertThatThrownBy(() -> orchestrator.trigger(CollectJobType.WEEKLY, null, TriggerType.API))
        .isInstanceOf(CollectRequestException.class);
    assertThatThrownBy(() -> orchestrator.trigger(CollectJobType.MASTER,
        BackfillRequest.forTickers(List.of("bad")), TriggerType.API))
        .isInstanceOf(CollectRequestException.class).hasMessageContaining("종목코드");

    properties.setAppKey("");
    assertThatThrownBy(() -> orchestrator.trigger(CollectJobType.MASTER, null, TriggerType.API))
        .isInstanceOf(CollectRequestException.class).hasMessageContaining("KIS_APP_KEY");
    verify(runService, never()).start(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("취소된 run 은 finish 를 호출하지 않는다 (CANCELED 보호)")
  void canceledRunIsNotFinished() {
    when(runService.isCancelRequested(7L)).thenReturn(true);
    CollectJob job = job(CollectJobType.MASTER, exec -> {
      assertThat(exec.isCancelRequested()).isTrue();
    });

    orchestrator(job, Runnable::run).trigger(CollectJobType.MASTER, null, TriggerType.API);

    verify(runService, never()).finish(anyLong(), any(), any());
    verify(runService).updateMetadata(eq(7L), any());
  }

  @Test
  @DisplayName("상위 run 이 취소되면 하위 run 은 CANCELED 로 닫힌다 (자기 run 취소는 finish 생략)")
  void parentCancelClosesChildRun() {
    when(runService.isCancelRequested(7L)).thenReturn(false);
    when(runService.isCancelRequested(99L)).thenReturn(true);
    CollectJob job = job(CollectJobType.MASTER, exec -> {
      assertThat(exec.isCancelRequested()).isTrue();
      assertThat(exec.isCanceledByParent()).isTrue();
    });

    StockCollectOrchestrator.TriggerResult result = orchestrator(job, Runnable::run)
        .trigger(CollectJobType.MASTER, null, TriggerType.SCHEDULER, 99L);

    assertThat(result.async()).isFalse();
    verify(runService).finish(eq(7L), eq(CollectStatus.CANCELED), any(String.class));
  }
}

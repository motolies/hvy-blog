package kr.hvy.blog.modules.stock.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.common.config.executor.TraceExecutors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 종목 단위 작업을 가상 스레드 N개(kis.backfill.concurrency)로 돌린다. KIS 한도는 전역 레이트 리미터가 지키므로
 * 여기서는 동시 진행 수만 제한한다. 취소가 감지되면 새 제출을 멈추고 진행 중인 것만 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcurrentTargetRunner {

  private final KisProperties properties;

  /**
   * 대상들을 동시성 제한 아래 처리한다. task 는 스스로 예외를 잡아 실패를 기록해야 한다.
   * <p>
   * 풀은 {@link TraceExecutors} 로 만든다 — {@code Executors.newVirtualThreadPerTaskExecutor()} 로 직접
   * 만들면 Spring 이 모르는 풀이라 데코레이터가 붙지 않아 종목 단위 로그에서 traceId 가 전부 사라진다.
   * {@code ContextExecutorService} 는 submit 시점마다 스냅샷을 떠서 호출 스레드의 트레이스 컨텍스트를 넘긴다.
   */
  public <T> void run(CollectExecution execution, List<T> targets, Consumer<T> task) {
    int concurrency = Math.max(1, properties.getBackfill().getConcurrency());
    Semaphore gate = new Semaphore(concurrency);
    List<Future<?>> futures = new ArrayList<>();
    try (ExecutorService pool = TraceExecutors.virtualThreadPerTask("kis-target-")) {
      for (T target : targets) {
        if (execution.isCancelRequested()) {
          break;
        }
        gate.acquireUninterruptibly();
        futures.add(pool.submit(() -> {
          try {
            task.accept(target);
          } finally {
            gate.release();
          }
        }));
      }
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          log.error("수집 작업 스레드 예외(종목 단위 처리에서 잡히지 않은 오류)", e.getCause());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }
}

package kr.hvy.blog.modules.stock.application.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 수집 잡 실행의 단일 진입점. run 생성(중복 차단) → 잡 본문 → 카운터 flush → 종료 상태 → Slack 까지 감싼다.
 * <p>
 * 관리자 API 가 장시간 잡(백필)을 트리거하면 {@code kisBackfillExecutor} 에 제출하고 즉시 돌아간다(202).
 * 스케줄러와 짧은 잡은 호출 스레드에서 동기 실행한다. 잡 본문이 예외로 죽어도 run 은 반드시 종료 상태가 된다.
 */
@Slf4j
@Service
public class StockCollectOrchestrator {

  private final Map<CollectJobType, CollectJob> jobs = new EnumMap<>(CollectJobType.class);
  private final CollectRunService runService;
  private final CollectNotifier notifier;
  private final KisProperties properties;
  private final Executor backfillExecutor;

  public StockCollectOrchestrator(List<CollectJob> jobList, CollectRunService runService, CollectNotifier notifier,
      KisProperties properties, @Qualifier("kisBackfillExecutor") Executor backfillExecutor) {
    this.runService = runService;
    this.notifier = notifier;
    this.properties = properties;
    this.backfillExecutor = backfillExecutor;
    for (CollectJob job : jobList) {
      CollectJob previous = jobs.putIfAbsent(job.jobType(), job);
      if (previous != null) {
        throw new IllegalStateException("잡 유형 중복 등록: " + job.jobType());
      }
    }
    log.info("주식 수집 잡 등록: {}", jobs.keySet());
  }

  /**
   * 트리거 결과. async=true 면 run 은 RUNNING 상태로 돌아가고 백그라운드에서 진행된다.
   */
  public record TriggerResult(StockCollectRun run, boolean async) {
  }

  /**
   * 잡을 실행한다. 이미 RUNNING 이면 {@link CollectAlreadyRunningException}, 요청이 잘못되면 {@link CollectRequestException}.
   */
  public TriggerResult trigger(CollectJobType jobType, BackfillRequest request, TriggerType triggerType) {
    return trigger(jobType, request, triggerType, null);
  }

  /**
   * 상위 run 아래에서 하위 잡을 실행한다(BACKFILL_ALL). 상위가 취소되면 하위도 종목 경계에서 멈춘다.
   * 하위는 항상 호출 스레드에서 동기 실행된다.
   */
  public TriggerResult trigger(CollectJobType jobType, BackfillRequest request, TriggerType triggerType, Long parentRunId) {
    BackfillRequest effective = request == null ? BackfillRequest.empty() : request;
    CollectJob job = jobs.get(jobType);
    if (job == null) {
      throw new CollectRequestException("실행 가능한 잡이 아닙니다: " + jobType);
    }
    if (!properties.isConfigured()) {
      throw new CollectRequestException("KIS 앱키가 설정되지 않았습니다 (KIS_APP_KEY/KIS_APP_SECRET)");
    }
    effective.validate();

    StockCollectRun run;
    try {
      run = runService.start(jobType, triggerType, MarketClock.today(), effective.startDate(), effective.endDate(),
          effective.toMetadata());
    } catch (DataIntegrityViolationException e) {
      // 사전 조회와 INSERT 사이에 끼어든 경합: 부분 유니크 인덱스가 막았다. 롤백된 뒤 새 트랜잭션에서 id 를 찾아 409 로 돌린다
      throw new CollectAlreadyRunningException(jobType, runService.findRunningId(jobType).orElse(null));
    }
    CollectExecution execution = new CollectExecution(run, effective, runService, parentRunId);

    boolean async = triggerType == TriggerType.API && jobType.isLongRunning() && parentRunId == null;
    if (!async) {
      execute(job, execution);
      return new TriggerResult(runService.get(run.getRunId()), false);
    }
    try {
      backfillExecutor.execute(() -> execute(job, execution));
    } catch (RejectedExecutionException e) {
      runService.finish(run.getRunId(), CollectStatus.FAILED, "백필 실행기 큐 포화로 거부됨");
      throw new CollectRequestException("백필 실행기 큐가 가득 찼습니다. 실행 중인 백필이 끝난 뒤 다시 시도하세요");
    }
    return new TriggerResult(run, true);
  }

  /**
   * 등록된 잡인지.
   */
  public boolean supports(CollectJobType jobType) {
    return jobs.containsKey(jobType);
  }

  /**
   * 잡 본문 + 종료 처리. 취소된 run 은 이미 CANCELED 이므로 finish 를 건너뛴다(CollectRunService 가 덮어쓰지 않음).
   */
  void execute(CollectJob job, CollectExecution execution) {
    Long runId = execution.runId();
    CollectJobType jobType = execution.run().getJobType();
    log.info("### 주식 수집 시작: runId={}, job={} ###", runId, jobType);
    try {
      job.execute(execution);
      flushOrRecord(execution);
      CollectStatus status = notifier.decideStatus(execution);
      runService.updateMetadata(runId, execution.metadataSnapshot());
      if (execution.isCancelRequested()) {
        if (execution.isCanceledByParent()) {
          // 자기 run 은 아직 RUNNING 이므로 상위 취소를 반영해 닫는다
          runService.finish(runId, CollectStatus.CANCELED, "상위 run " + execution.parentRunId() + " 취소로 중단");
        }
        log.info("### 주식 수집 취소 종료: runId={}, job={}, processed={}, byParent={} ###",
            runId, jobType, execution.processedCount(), execution.isCanceledByParent());
        return;
      }
      runService.finish(runId, status, execution.failureSummary());
      log.info("### 주식 수집 종료: runId={}, job={}, status={}, processed={}, rows={}, failures={}, flushFailures={} ###",
          runId, jobType, status, execution.processedCount(), execution.totalRows(), execution.failureCount(),
          execution.flushFailures());
      notifier.afterRun(runService.get(runId), execution, status);
    } catch (Exception e) {
      log.error("### 주식 수집 실패: runId={}, job={} ###", runId, jobType, e);
      flushOrRecord(execution);
      runService.updateMetadata(runId, execution.metadataSnapshot());
      runService.finish(runId, CollectStatus.FAILED, e.toString());
      notifier.afterFailure(runService.get(runId), execution, e);
    }
  }

  /**
   * 최종 flush. 실패하면 값이 보존돼 있으므로 즉시 한 번 더 시도하고(순간 끊김 흡수), 그래도 안 되면 단계 실패로 기록해
   * PARTIAL 판정과 알림에 드러나게 한다. 예외를 던지지 않으므로 잡 본문이 성공한 run 을 FAILED 로 만들지 않는다.
   */
  private void flushOrRecord(CollectExecution execution) {
    if (execution.flush() || execution.flush()) {
      return;
    }
    RuntimeException cause = execution.lastFlushError();
    execution.recordFailure("STEP:FLUSH", "run 카운터 반영 실패: " + cause);
    log.error("run 카운터 최종 flush 실패: runId={}, flushFailures={}", execution.runId(), execution.flushFailures(), cause);
  }
}

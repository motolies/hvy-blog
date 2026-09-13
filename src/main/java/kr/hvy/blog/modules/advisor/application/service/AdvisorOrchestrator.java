package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * advisor 잡 실행의 단일 진입점. run 생성(중복 차단) → 잡 본문 → 사용량·메타 기록 → 종료 상태 → Slack.
 * <p>
 * 관리자 API 가 장시간 잡을 트리거하면 advisorExecutor 에 제출하고 즉시 돌아간다(202). 스케줄러는 호출 스레드에서 동기 실행한다(ShedLock 락 유지).
 * 잡 본문이 예외로 죽어도 run 은 반드시 종료 상태가 되고, 스케줄 트리거가 run 생성 전에 거부되면 #hvy-error 로 알린 뒤 다시 던진다
 * (AbstractScheduler 가 예외를 로그로만 삼키기 때문 — stock 모듈 2026-09-13 교훈).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorOrchestrator {

  private final Map<AdvisorJobType, AdvisorJob> jobs = new EnumMap<>(AdvisorJobType.class);
  private final AdvisorRunService runService;
  private final AdvisorNotifier notifier;
  private final AdvisorProperties properties;
  private final Executor executor;

  public AdvisorOrchestrator(List<AdvisorJob> jobList, AdvisorRunService runService, AdvisorNotifier notifier,
      AdvisorProperties properties, @Qualifier("advisorExecutor") Executor executor) {
    this.runService = runService;
    this.notifier = notifier;
    this.properties = properties;
    this.executor = executor;
    for (AdvisorJob job : jobList) {
      AdvisorJob previous = jobs.putIfAbsent(job.jobType(), job);
      if (previous != null) {
        throw new IllegalStateException("advisor 잡 유형 중복 등록: " + job.jobType());
      }
    }
    log.info("AI 판단 잡 등록: {}", jobs.keySet());
  }

  /**
   * 트리거 결과. async=true 면 run 은 RUNNING 상태로 돌아가고 백그라운드에서 진행된다.
   */
  public record TriggerResult(AdvisorRun run, boolean async) {
  }

  /**
   * 기동 시 RUNNING 잔여를 FAILED 로 정리한다 (단일 인스턴스).
   */
  @EventListener(ApplicationReadyEvent.class)
  public void reconcileOnStartup() {
    int reconciled = runService.reconcileStaleRuns();
    if (reconciled > 0) {
      log.warn("기동 시 RUNNING 상태 advisor run {}건을 FAILED 로 정리했습니다", reconciled);
    }
  }

  /**
   * 잡을 실행한다. baseDate 가 null 이면 KST 오늘. 이미 RUNNING 이면 {@link AdvisorAlreadyRunningException},
   * 설정 누락·미등록 잡은 {@link AdvisorRequestException}.
   */
  public TriggerResult trigger(AdvisorJobType jobType, LocalDate baseDate, AdvisorTriggerType triggerType) {
    LocalDate effectiveDate = baseDate == null ? MarketClock.today() : baseDate;
    boolean scheduled = triggerType == AdvisorTriggerType.SCHEDULER;
    AdvisorJob job;
    AdvisorRun run;
    try {
      job = jobs.get(jobType);
      if (job == null) {
        throw new AdvisorRequestException("실행 가능한 잡이 아닙니다: " + jobType);
      }
      if (!properties.isConfigured()) {
        throw new AdvisorRequestException("advisor 설정이 비어 있습니다 (OPENAI_API_KEY, ADVISOR_JUDGE_MODEL, ADVISOR_ASSIST_MODEL)");
      }
      if (effectiveDate.isAfter(MarketClock.today())) {
        throw new AdvisorRequestException("미래 날짜는 판단할 수 없습니다: " + effectiveDate);
      }
      try {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("baseDate", effectiveDate.toString());
        metadata.put("requested", baseDate != null);
        run = runService.start(jobType, triggerType, effectiveDate, metadata);
      } catch (DataIntegrityViolationException e) {
        // 사전 조회와 INSERT 사이에 끼어든 경합: 부분 유니크 인덱스가 막았다. 새 트랜잭션에서 id 를 찾아 409 로 돌린다
        throw new AdvisorAlreadyRunningException(jobType, runService.findRunningId(jobType).orElse(null));
      }
    } catch (RuntimeException e) {
      if (scheduled) {
        notifier.afterTriggerRejected(jobType, e);
      }
      throw e;
    }
    AdvisorExecution execution = new AdvisorExecution(run, effectiveDate, properties);

    boolean async = triggerType == AdvisorTriggerType.API && jobType.isLongRunning();
    if (!async) {
      execute(job, execution);
      return new TriggerResult(runService.get(run.getRunId()), false);
    }
    try {
      executor.execute(() -> execute(job, execution));
    } catch (RejectedExecutionException e) {
      runService.finish(run.getRunId(), AdvisorStatus.FAILED, "advisor 실행기 큐 포화로 거부됨");
      throw new AdvisorRequestException("advisor 실행기 큐가 가득 찼습니다. 실행 중인 잡이 끝난 뒤 다시 시도하세요");
    }
    return new TriggerResult(run, true);
  }

  /**
   * 등록된 잡인지.
   */
  public boolean supports(AdvisorJobType jobType) {
    return jobs.containsKey(jobType);
  }

  /**
   * 잡 본문 + 종료 처리. 어떤 경로로든 run 은 종료 상태가 된다.
   */
  void execute(AdvisorJob job, AdvisorExecution execution) {
    Long runId = execution.runId();
    AdvisorJobType jobType = execution.run().getJobType();
    log.info("### AI 판단 시작: runId={}, job={}, base={} ###", runId, jobType, execution.baseDate());
    try {
      job.execute(execution);
      AdvisorStatus status = execution.decideStatus();
      persist(execution);
      runService.finish(runId, status, execution.isSkipped() ? execution.skipReason() : execution.summary());
      log.info("### AI 판단 종료: runId={}, job={}, status={}, llmCalls={}, tokens in/out={}/{}, warnings={} ###",
          runId, jobType, status, execution.llmCalls(), execution.promptTokens(), execution.completionTokens(),
          execution.warnings().size());
      notifier.afterRun(runService.get(runId), execution, status);
    } catch (Exception e) {
      log.error("### AI 판단 실패: runId={}, job={} ###", runId, jobType, e);
      persist(execution);
      runService.finish(runId, AdvisorStatus.FAILED, e.toString());
      notifier.afterFailure(runService.get(runId), execution, e);
    }
  }

  /**
   * 메타데이터·사용량을 run 에 반영한다 (실패해도 잡 결과를 바꾸지 않는다).
   */
  private void persist(AdvisorExecution execution) {
    try {
      runService.updateMetadata(execution.runId(), execution.metadataSnapshot());
      if (execution.llmCalls() > 0) {
        runService.recordUsage(execution.runId(), execution.model(), execution.promptVersion(), execution.llmCalls(),
            execution.promptTokens(), execution.completionTokens(), execution.reasoningTokens(), execution.cachedTokens(),
            execution.costUsd());
      }
    } catch (RuntimeException e) {
      log.error("advisor run 메타·사용량 기록 실패(무시): runId={}", execution.runId(), e);
    }
  }
}

package kr.hvy.blog.modules.advisor.application.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.repository.AdvisorRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * advisor run 생명주기. 종료 처리는 {@code REQUIRES_NEW} 로 분리해 본 작업이 롤백되어도 "시도했다"는 기록이 남게 한다 (CollectRunService 와 같은 구조).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisorRunService {

  private final AdvisorRunRepository repository;

  /**
   * run 을 RUNNING 으로 생성한다. 같은 잡이 이미 RUNNING 이면 먼저 조회해 {@link AdvisorAlreadyRunningException} 을 던지고,
   * 그 사이에 끼어든 경합은 부분 유니크 인덱스가 막는다(그때의 DataIntegrityViolationException 은 호출자가 변환한다).
   * 제약 위반을 이 안에서 잡고 재조회하면 안 된다 — 실패한 엔티티가 영속성 컨텍스트에 남아 auto-flush 에서 죽는다(stock 모듈 2026-09-08 교훈).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AdvisorRun start(AdvisorJobType jobType, AdvisorTriggerType triggerType, LocalDate baseDate, Map<String, Object> metadata) {
    repository.findFirstByJobTypeAndStatus(jobType, AdvisorStatus.RUNNING).ifPresent(running -> {
      throw new AdvisorAlreadyRunningException(jobType, running.getRunId());
    });
    AdvisorRun run = AdvisorRun.builder()
        .jobType(jobType)
        .triggerType(triggerType)
        .baseDate(baseDate)
        .metadataJson(metadata)
        .build();
    return repository.saveAndFlush(run);
  }

  /**
   * 잡의 RUNNING run id (경합 409 응답용, 새 트랜잭션).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<Long> findRunningId(AdvisorJobType jobType) {
    return repository.findFirstByJobTypeAndStatus(jobType, AdvisorStatus.RUNNING).map(AdvisorRun::getRunId);
  }

  /**
   * 종료 상태로 전환한다. 이미 종료 상태면 덮어쓰지 않는다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(Long runId, AdvisorStatus terminalStatus, String errorMessage) {
    AdvisorRun run = repository.findById(runId).orElseThrow(() -> notFound(runId));
    if (!run.isRunning()) {
      log.info("advisor run 이 이미 종료 상태라 finish 를 건너뜁니다: runId={}, status={}", runId, run.getStatus());
      return;
    }
    run.finish(terminalStatus, errorMessage);
  }

  /**
   * 협조적 취소를 요청한다. RUNNING 이면 CANCELED 로 닫고 true. 잡은 단계·IC 청크 경계마다 {@link #isCancelRequested} 를 폴링해 스스로 빠져나온다
   * (실행 중인 SQL 은 끊지 못한다 — 그건 pg_cancel_backend). CollectRunService.requestCancel 과 같은 구조.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean requestCancel(Long runId) {
    AdvisorRun run = repository.findById(runId).orElseThrow(() -> notFound(runId));
    if (!run.isRunning()) {
      return false;
    }
    run.finish(AdvisorStatus.CANCELED, "관리자 취소 요청");
    return true;
  }

  /**
   * 취소가 요청되었는지 (새 트랜잭션으로 최신 값을 읽는다).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean isCancelRequested(Long runId) {
    return repository.findById(runId).map(run -> run.getStatus() == AdvisorStatus.CANCELED).orElse(false);
  }

  /**
   * 부가 정보를 갱신한다 (단계 경계마다 + 종료 시).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateMetadata(Long runId, Map<String, Object> metadata) {
    repository.findById(runId).ifPresent(run -> run.updateMetadata(metadata));
  }

  /**
   * LLM 사용량을 기록한다 (종료 시 1회).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordUsage(Long runId, String model, String promptVersion, int llmCalls, int promptTokens, int completionTokens,
      int reasoningTokens, int cachedTokens, BigDecimal costUsd) {
    repository.findById(runId).ifPresent(run -> run.recordUsage(model, promptVersion, llmCalls, promptTokens, completionTokens,
        reasoningTokens, cachedTokens, costUsd));
  }

  /**
   * 기동 시 RUNNING run 을 전부 FAILED 로 정리한다 (단일 인스턴스 전제, 프로세스 강제 종료 복구).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int reconcileStaleRuns() {
    Instant now = Instant.now();
    return repository.reconcileStale(AdvisorStatus.RUNNING, AdvisorStatus.FAILED, now.plusSeconds(1), now,
        "재기동으로 중단된 실행 (기동 시 RUNNING 전부 정리)");
  }

  @Transactional(readOnly = true)
  public List<AdvisorRun> findRecent(AdvisorJobType jobType, int limit) {
    PageRequest page = PageRequest.of(0, limit);
    return jobType == null
        ? repository.findAllByOrderByStartedAtDesc(page)
        : repository.findAllByJobTypeOrderByStartedAtDesc(jobType, page);
  }

  @Transactional(readOnly = true)
  public AdvisorRun get(Long runId) {
    return repository.findById(runId).orElseThrow(() -> notFound(runId));
  }

  private NoSuchElementException notFound(Long runId) {
    return new NoSuchElementException("advisor run 을 찾을 수 없습니다: runId=" + runId);
  }
}

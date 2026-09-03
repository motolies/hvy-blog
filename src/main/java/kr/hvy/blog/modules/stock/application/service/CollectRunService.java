package kr.hvy.blog.modules.stock.application.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import kr.hvy.blog.modules.stock.client.KisCallStats;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 run 생명주기. 카운터·종료 처리는 {@code REQUIRES_NEW} 로 분리해 본 작업이 롤백되어도 "시도했다"는 기록이 남게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectRunService {

  private final StockCollectRunRepository repository;

  /**
   * run 을 RUNNING 으로 생성한다. 같은 잡이 이미 RUNNING 이면 부분 유니크 인덱스가 막고
   * {@link CollectAlreadyRunningException} 으로 변환한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public StockCollectRun start(CollectJobType jobType, TriggerType triggerType, LocalDate targetDate,
      LocalDate rangeStart, LocalDate rangeEnd, Map<String, Object> metadata) {
    StockCollectRun run = StockCollectRun.builder()
        .jobType(jobType)
        .triggerType(triggerType)
        .targetDate(targetDate)
        .rangeStart(rangeStart)
        .rangeEnd(rangeEnd)
        .metadataJson(metadata)
        .build();
    try {
      return repository.saveAndFlush(run);
    } catch (DataIntegrityViolationException e) {
      Long runningId = repository.findFirstByJobTypeAndStatus(jobType, CollectStatus.RUNNING)
          .map(StockCollectRun::getRunId)
          .orElse(null);
      throw new CollectAlreadyRunningException(jobType, runningId);
    }
  }

  /**
   * 종료 상태로 전환한다. 이미 CANCELED 등 종료 상태면 덮어쓰지 않는다(취소 보호).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(Long runId, CollectStatus terminalStatus, String errorMessage) {
    StockCollectRun run = repository.findById(runId).orElseThrow(() -> notFound(runId));
    if (!run.isRunning()) {
      log.info("run 이 이미 종료 상태라 finish 를 건너뜁니다: runId={}, status={}", runId, run.getStatus());
      return;
    }
    run.finish(terminalStatus, errorMessage);
  }

  /**
   * 카운터를 누적한다. 백필 루프가 윈도우마다 호출하므로 원자적 UPDATE 로 처리한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void addCounters(Long runId, long rows, long apiCalls, long apiFails) {
    if (rows == 0 && apiCalls == 0 && apiFails == 0) {
      return;
    }
    repository.addCounters(runId, rows, apiCalls, apiFails, Instant.now());
  }

  /**
   * 호출 통계를 비우면서 run 카운터에 반영한다.
   */
  public void flushStats(Long runId, long rows, KisCallStats stats) {
    KisCallStats.Snapshot snapshot = stats.drain();
    addCounters(runId, rows, snapshot.apiCalls(), snapshot.apiFails());
  }

  /**
   * 부가 정보를 갱신한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateMetadata(Long runId, Map<String, Object> metadata) {
    repository.findById(runId).ifPresent(run -> run.updateMetadata(metadata));
  }

  /**
   * 협조적 취소를 요청한다. 잡 루프가 종목 경계마다 {@link #isCancelRequested} 를 폴링해 스스로 빠져나온다.
   *
   * @return RUNNING 이어서 CANCELED 로 바꿨으면 true
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean requestCancel(Long runId) {
    StockCollectRun run = repository.findById(runId).orElseThrow(() -> notFound(runId));
    if (!run.isRunning()) {
      return false;
    }
    run.finish(CollectStatus.CANCELED, "관리자 취소 요청");
    return true;
  }

  /**
   * 취소가 요청되었는지 확인한다 (새 트랜잭션으로 최신 값을 읽는다).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean isCancelRequested(Long runId) {
    return repository.findById(runId)
        .map(run -> run.getStatus() == CollectStatus.CANCELED)
        .orElse(false);
  }

  /**
   * 기동 시 RUNNING run 을 FAILED 로 정리한다 (프로세스 강제 종료 복구). olderThan 이 0 이면 전부 정리한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int reconcileStaleRuns(Duration olderThan) {
    Instant now = Instant.now();
    String message = olderThan.isZero()
        ? "재기동으로 중단된 실행 (기동 시 RUNNING 전부 정리, 체크포인트로 재개 가능)"
        : "기동 시 stale RUNNING 정리 (" + olderThan + " 초과)";
    // olderThan=0 이면 started_at < now 로 전부 잡힌다 (시계 오차 대비 1초 여유)
    return repository.reconcileStale(CollectStatus.RUNNING, CollectStatus.FAILED,
        now.minus(olderThan).plusSeconds(olderThan.isZero() ? 1 : 0), now, message);
  }

  @Transactional(readOnly = true)
  public List<StockCollectRun> findRecent(CollectJobType jobType, int limit) {
    PageRequest page = PageRequest.of(0, limit);
    return jobType == null
        ? repository.findAllByOrderByStartedAtDesc(page)
        : repository.findAllByJobTypeOrderByStartedAtDesc(jobType, page);
  }

  @Transactional(readOnly = true)
  public StockCollectRun get(Long runId) {
    return repository.findById(runId).orElseThrow(() -> notFound(runId));
  }

  private NoSuchElementException notFound(Long runId) {
    return new NoSuchElementException("수집 run 을 찾을 수 없습니다: runId=" + runId);
  }
}

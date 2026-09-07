package kr.hvy.blog.modules.stock.application.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisCallContext;
import kr.hvy.blog.modules.stock.client.KisCallStats;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 실행 중인 run 의 컨텍스트. 잡 본문이 행 수·실패·메타데이터를 여기에 쌓고, 주기적으로 run 카운터에 flush 한다.
 * flush 는 best-effort 라 실패해도 잡을 멈추지 않고 값을 보존해 다음 flush 에서 재시도한다.
 * 여러 가상 스레드에서 동시에 쓰이므로 스레드 안전하게 만든다.
 */
@Slf4j
public final class CollectExecution {

  /** 취소 여부 DB 조회 최소 간격 — 종목마다 조회하면 3시간 백필에 수천 번이라 캐시한다 */
  private static final long CANCEL_CHECK_INTERVAL_MS = 3_000L;
  private static final int FAILURE_SAMPLE = 3;

  private final StockCollectRun run;
  private final BackfillRequest request;
  private final KisCallContext callContext;
  private final CollectRunService runService;
  /** 상위 run(BACKFILL_ALL 등). 상위가 취소되면 이 실행도 취소로 본다 */
  private final Long parentRunId;
  private final List<CollectFailure> failures = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Object> metadata = new ConcurrentHashMap<>();
  private final AtomicLong pendingRows = new AtomicLong();
  private final AtomicLong totalRows = new AtomicLong();
  private final AtomicInteger processed = new AtomicInteger();
  private final AtomicInteger flushFailures = new AtomicInteger();
  private volatile RuntimeException lastFlushError;
  private volatile long lastCancelCheck = 0L;
  private volatile boolean canceled = false;
  private volatile boolean canceledByParent = false;

  public CollectExecution(StockCollectRun run, BackfillRequest request, CollectRunService runService) {
    this(run, request, runService, null);
  }

  public CollectExecution(StockCollectRun run, BackfillRequest request, CollectRunService runService, Long parentRunId) {
    this.run = run;
    this.request = request == null ? BackfillRequest.empty() : request;
    this.runService = runService;
    this.parentRunId = parentRunId;
    this.callContext = KisCallContext.of(run.getRunId());
  }

  public Long parentRunId() {
    return parentRunId;
  }

  /**
   * 취소가 자기 run 이 아니라 상위 run 취소로 전파된 것인지. 이 경우 오케스트레이터가 이 run 을 CANCELED 로 닫아야 한다.
   */
  public boolean isCanceledByParent() {
    return canceledByParent;
  }

  /** 종목 단위 실패 1건 */
  public record CollectFailure(String target, String message) {
  }

  public Long runId() {
    return run.getRunId();
  }

  public StockCollectRun run() {
    return run;
  }

  public BackfillRequest request() {
    return request;
  }

  /**
   * 대상 종목이 지정된 호출 컨텍스트 (통계는 run 전체가 공유).
   */
  public KisCallContext context(String target) {
    return callContext.withTarget(target);
  }

  /**
   * 적재 행 수를 누적한다.
   */
  public void addRows(long rows) {
    if (rows > 0) {
      pendingRows.addAndGet(rows);
      totalRows.addAndGet(rows);
    }
  }

  /**
   * 대상 1개(종목/지수) 처리 완료.
   */
  public void targetDone() {
    processed.incrementAndGet();
  }

  /**
   * 누적 행 수와 호출 통계를 run 카운터에 반영한다 (best-effort).
   * <p>
   * 실패해도 예외를 던지지 않고 비워 둔 값을 되돌려, 다음 flush 가 누적값으로 자연 재시도한다. drain·restore 를
   * 트랜잭션 밖(이 객체)에서 하므로 커밋 시점 실패까지 덮는다. 커밋은 됐는데 응답이 유실된 경우엔 restore 로 소량
   * 과계상될 수 있으나, 관측용 카운터라 유실보다 과계상(at-least-once)을 택한다.
   *
   * @return 반영했거나 반영할 값이 없으면 true, 실패해 값을 보존했으면 false
   */
  public boolean flush() {
    long rows = pendingRows.getAndSet(0);
    KisCallStats.Snapshot snapshot = callContext.stats().drain();
    if (rows == 0 && snapshot.isEmpty()) {
      return true;
    }
    try {
      runService.addCounters(run.getRunId(), rows, snapshot.apiCalls(), snapshot.apiFails());
      return true;
    } catch (RuntimeException e) {
      pendingRows.addAndGet(rows);
      callContext.stats().restore(snapshot);
      lastFlushError = e;
      // 종목마다 flush 하므로 첫 실패만 스택을 남기고 이후는 카운터로만 센다 (요약은 오케스트레이터 종료 로그)
      if (flushFailures.incrementAndGet() == 1) {
        log.warn("run 카운터 flush 실패(값 보존, 다음 flush 에서 재시도): runId={}, rows={}, apiCalls={}, apiFails={}",
            run.getRunId(), rows, snapshot.apiCalls(), snapshot.apiFails(), e);
      }
      return false;
    }
  }

  /**
   * flush 가 실패한 횟수 (재시도로 결국 반영됐어도 센다).
   */
  public int flushFailures() {
    return flushFailures.get();
  }

  /**
   * 마지막 flush 실패 원인 (없으면 null).
   */
  public RuntimeException lastFlushError() {
    return lastFlushError;
  }

  /**
   * 취소가 요청되었는지 (3초 캐시). 자기 run 또는 상위 run 이 CANCELED 면 true 이고, 한 번 true 가 되면 계속 true 다.
   */
  public boolean isCancelRequested() {
    return isCancelRequested(false);
  }

  /**
   * 취소 여부를 캐시 없이 즉시 조회한다. 단계 사이처럼 드물게 호출되는 경계에서 쓴다.
   */
  public boolean checkCancelNow() {
    return isCancelRequested(true);
  }

  private boolean isCancelRequested(boolean fresh) {
    if (canceled) {
      return true;
    }
    long now = System.currentTimeMillis();
    if (!fresh && now - lastCancelCheck < CANCEL_CHECK_INTERVAL_MS) {
      return false;
    }
    lastCancelCheck = now;
    if (runService.isCancelRequested(run.getRunId())) {
      canceled = true;
    } else if (parentRunId != null && runService.isCancelRequested(parentRunId)) {
      canceled = true;
      canceledByParent = true;
    }
    return canceled;
  }

  /**
   * 종목 단위 실패를 기록한다 (잡은 계속 진행).
   */
  public void recordFailure(String target, String message) {
    failures.add(new CollectFailure(target, StringUtils.abbreviate(message, 300)));
  }

  public List<CollectFailure> failures() {
    return List.copyOf(failures);
  }

  public int failureCount() {
    return failures.size();
  }

  public int processedCount() {
    return processed.get();
  }

  public long totalRows() {
    return totalRows.get();
  }

  public long rateLimitHits() {
    return callContext.stats().getRateLimitHits().get();
  }

  public void putMetadata(String key, Object value) {
    if (value != null) {
      metadata.put(key, value);
    }
  }

  /**
   * 요청 요약 + 잡이 남긴 값 + 실패 표본을 합친 스냅샷.
   */
  public Map<String, Object> metadataSnapshot() {
    Map<String, Object> snapshot = new LinkedHashMap<>(request.toMetadata());
    snapshot.putAll(metadata);
    snapshot.put("processed", processed.get());
    snapshot.put("rows", totalRows.get());
    snapshot.put("failures", failures.size());
    int flushFailed = flushFailures.get();
    if (flushFailed > 0) {
      // 카운터 컬럼이 얼마나 덜 반영됐는지 남긴다 — 메타데이터는 벌크 UPDATE 가 아니라 결함 종류가 달라도 살아남을 수 있다
      snapshot.put("flushFailures", flushFailed);
      snapshot.put("lastFlushError", StringUtils.abbreviate(String.valueOf(lastFlushError), 300));
      snapshot.put("unflushedRows", pendingRows.get());
      snapshot.put("unflushedApiCalls", callContext.stats().getApiCalls().get());
      snapshot.put("unflushedApiFails", callContext.stats().getApiFails().get());
    }
    if (!failures.isEmpty()) {
      List<Map<String, String>> sample = new ArrayList<>();
      for (CollectFailure failure : failures.subList(0, Math.min(FAILURE_SAMPLE, failures.size()))) {
        sample.add(Map.of("target", String.valueOf(failure.target()), "message", failure.message()));
      }
      snapshot.put("failureSample", sample);
    }
    return snapshot;
  }

  /**
   * run.error_message 용 실패 요약 (없으면 null).
   */
  public String failureSummary() {
    if (failures.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder(failures.size() + "건 실패");
    List<CollectFailure> copy = failures();
    for (CollectFailure failure : copy.subList(0, Math.min(FAILURE_SAMPLE, copy.size()))) {
      sb.append("\n- ").append(failure.target()).append(": ").append(failure.message());
    }
    return sb.toString();
  }
}

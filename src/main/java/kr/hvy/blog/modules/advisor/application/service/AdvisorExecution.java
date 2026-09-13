package kr.hvy.blog.modules.advisor.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 실행 중인 advisor run 의 컨텍스트. 잡 본문이 단계 결과·경고·부분 실패·LLM 사용량을 여기에 쌓고 오케스트레이터가 run 에 반영한다.
 * <p>
 * 오케스트레이터가 {@link #bind} 로 붙여 주는 flush(단계마다 메타 저장) 와 취소 프로브(협조적 취소) 는 잡 본문이 직접 부르지 않고
 * {@link AdvisorSteps} 가 단계 경계에서 부른다. 테스트가 만드는 미바인딩 execution 은 둘 다 no-op 이다.
 * 종료 시 1회만 기록하던 2026-09-13 이전 방식은 IC 6년치 계산 중 진행이 전혀 보이지 않는 문제를 낳았다.
 */
@Slf4j
public final class AdvisorExecution {

  private static final int SAMPLE = 3;
  /** 취소 프로브(DB 조회) 캐시 간격 — 청크·단계 경계는 드물어 3초면 충분하다 */
  static final long CANCEL_CHECK_INTERVAL_MS = 3_000L;

  /** 단계 1개의 결과 (예외로 죽은 단계는 FAILED, 격리된 채 다음 단계로 진행) */
  public record StepResult(String name, String status, long millis) {

    public boolean failed() {
      return "FAILED".equals(status);
    }
  }

  /** 대상 단위 부분 실패 1건 (채점 행·후보 등) */
  public record Failure(String target, String message) {
  }

  private final AdvisorRun run;
  private final LocalDate baseDate;
  private final AdvisorProperties properties;
  private final Map<String, Object> metadata = new ConcurrentHashMap<>();
  private final List<StepResult> steps = Collections.synchronizedList(new ArrayList<>());
  private final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());
  private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger promptTokens = new AtomicInteger();
  private final AtomicInteger completionTokens = new AtomicInteger();
  private final AtomicInteger reasoningTokens = new AtomicInteger();
  private final AtomicInteger cachedTokens = new AtomicInteger();
  private volatile String model;
  private volatile String promptVersion;
  private volatile String skipReason;
  private volatile boolean partial;
  private volatile Runnable flushSink;
  private volatile BooleanSupplier cancelProbe;
  private volatile boolean canceled;
  private volatile long lastCancelCheck;

  public AdvisorExecution(AdvisorRun run, LocalDate baseDate, AdvisorProperties properties) {
    this.run = run;
    this.baseDate = baseDate;
    this.properties = properties;
  }

  /**
   * 오케스트레이터가 실행 직전에 붙인다: flush = 현재 메타 스냅샷을 run 에 저장, cancelProbe = run 이 CANCELED 로 바뀌었는지 조회.
   */
  public void bind(Runnable flush, BooleanSupplier cancelProbe) {
    this.flushSink = flush;
    this.cancelProbe = cancelProbe;
  }

  /**
   * 단계 경계에서 진행 상황을 run 에 저장한다. 저장 실패는 잡 결과를 바꾸지 않으므로 로그만 남긴다. 미바인딩이면 아무 일도 없다.
   */
  public void flush() {
    Runnable sink = flushSink;
    if (sink == null) {
      return;
    }
    try {
      sink.run();
    } catch (RuntimeException e) {
      log.warn("advisor run 진행 기록 실패(무시): runId={}", runId(), e);
    }
  }

  /**
   * 취소가 요청되었는지 (3초 캐시). 한 번 true 가 되면 계속 true 다.
   */
  public boolean isCancelRequested() {
    return isCancelRequested(false);
  }

  /**
   * 취소 여부를 캐시 없이 즉시 조회한다 — 단계·청크 경계처럼 드물게 불리는 곳에서 쓴다.
   */
  public boolean checkCancelNow() {
    return isCancelRequested(true);
  }

  private boolean isCancelRequested(boolean fresh) {
    if (canceled) {
      return true;
    }
    BooleanSupplier probe = cancelProbe;
    if (probe == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (!fresh && now - lastCancelCheck < CANCEL_CHECK_INTERVAL_MS) {
      return false;
    }
    lastCancelCheck = now;
    if (probe.getAsBoolean()) {
      canceled = true;
    }
    return canceled;
  }

  public Long runId() {
    return run.getRunId();
  }

  public AdvisorRun run() {
    return run;
  }

  /** 판단·채점 기준 거래일 (요청이 없으면 오늘) */
  public LocalDate baseDate() {
    return baseDate;
  }

  public AdvisorProperties properties() {
    return properties;
  }

  /**
   * 게이트 미충족으로 본문을 돌리지 않고 끝낸다. run 은 SKIPPED 로 닫힌다.
   */
  public void skip(String reason) {
    this.skipReason = reason;
    putMetadata("skipped", reason);
  }

  public boolean isSkipped() {
    return skipReason != null;
  }

  public String skipReason() {
    return skipReason;
  }

  /**
   * run 을 PARTIAL 로 닫아야 하는 사유를 남긴다 (가드 제거율 초과·섀도 실패 등, 발행은 됐지만 완전하지 않음).
   */
  public void markPartial(String reason) {
    this.partial = true;
    warn(reason);
  }

  /**
   * 사람이 봐야 할 경고. 메타데이터와 Slack 요약에 실린다.
   */
  public void warn(String message) {
    warnings.add(StringUtils.abbreviate(message, 300));
  }

  public List<String> warnings() {
    return List.copyOf(warnings);
  }

  /**
   * 대상 단위 부분 실패를 기록한다 (잡은 계속 진행).
   */
  public void recordFailure(String target, String message) {
    failures.add(new Failure(target, StringUtils.abbreviate(message, 300)));
  }

  public List<Failure> failures() {
    return List.copyOf(failures);
  }

  public int failureCount() {
    return failures.size();
  }

  /**
   * 단계 결과를 기록한다 (AdvisorSteps 전용).
   */
  public void recordStep(String name, String status, long millis) {
    steps.add(new StepResult(name, status, millis));
  }

  public List<StepResult> steps() {
    return List.copyOf(steps);
  }

  /**
   * LLM 호출 1건의 사용량을 누적한다. reasoning·cached 는 OpenAI native usage 에서 꺼내 준다(없으면 0).
   */
  public void recordLlmUsage(String model, String promptVersion, Usage usage, int reasoning, int cached) {
    this.model = model;
    this.promptVersion = promptVersion;
    llmCalls.incrementAndGet();
    if (usage != null) {
      promptTokens.addAndGet(nvl(usage.getPromptTokens()));
      completionTokens.addAndGet(nvl(usage.getCompletionTokens()));
    }
    reasoningTokens.addAndGet(reasoning);
    cachedTokens.addAndGet(cached);
  }

  private static int nvl(Integer value) {
    return value == null ? 0 : value;
  }

  public String model() {
    return model;
  }

  public String promptVersion() {
    return promptVersion;
  }

  public int llmCalls() {
    return llmCalls.get();
  }

  public int promptTokens() {
    return promptTokens.get();
  }

  public int completionTokens() {
    return completionTokens.get();
  }

  public int reasoningTokens() {
    return reasoningTokens.get();
  }

  public int cachedTokens() {
    return cachedTokens.get();
  }

  /**
   * advisor.cost 단가로 계산한 비용(USD). 단가가 0 이면 0.
   */
  public BigDecimal costUsd() {
    AdvisorProperties.Cost cost = properties.getCost();
    BigDecimal in = cost.getInputPer1mUsd().multiply(BigDecimal.valueOf(promptTokens.get()));
    BigDecimal out = cost.getOutputPer1mUsd().multiply(BigDecimal.valueOf(completionTokens.get()));
    return in.add(out).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
  }

  public void putMetadata(String key, Object value) {
    if (value != null) {
      metadata.put(key, value);
    }
  }

  public Object metadata(String key) {
    return metadata.get(key);
  }

  /**
   * 종료 상태 판정: SKIPPED > FAILED(단계 예외로 결과가 없는 경우는 잡이 스스로 예외를 던진다) > PARTIAL > SUCCESS.
   */
  public AdvisorStatus decideStatus() {
    if (isSkipped()) {
      return AdvisorStatus.SKIPPED;
    }
    boolean anyStepFailed = steps.stream().anyMatch(StepResult::failed);
    return partial || anyStepFailed || !failures.isEmpty() ? AdvisorStatus.PARTIAL : AdvisorStatus.SUCCESS;
  }

  /**
   * 잡이 남긴 값 + 단계·경고·실패 표본을 합친 스냅샷.
   */
  public Map<String, Object> metadataSnapshot() {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("baseDate", baseDate == null ? null : baseDate.toString());
    snapshot.putAll(metadata);
    if (!steps.isEmpty()) {
      List<Map<String, Object>> list = new ArrayList<>();
      for (StepResult step : steps()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step.name());
        m.put("status", step.status());
        m.put("ms", step.millis());
        list.add(m);
      }
      snapshot.put("steps", list);
    }
    if (!warnings.isEmpty()) {
      snapshot.put("warnings", warnings());
    }
    if (!failures.isEmpty()) {
      snapshot.put("failures", failures.size());
      List<Map<String, String>> sample = new ArrayList<>();
      for (Failure failure : failures().subList(0, Math.min(SAMPLE, failures.size()))) {
        sample.add(Map.of("target", String.valueOf(failure.target()), "message", failure.message()));
      }
      snapshot.put("failureSample", sample);
    }
    if (llmCalls.get() > 0) {
      snapshot.put("llmCalls", llmCalls.get());
      snapshot.put("promptTokens", promptTokens.get());
      snapshot.put("completionTokens", completionTokens.get());
      snapshot.put("reasoningTokens", reasoningTokens.get());
      snapshot.put("cachedTokens", cachedTokens.get());
    }
    snapshot.values().removeIf(v -> v == null);
    return snapshot;
  }

  /**
   * run.error_message 용 요약 (경고·실패가 없으면 null).
   */
  public String summary() {
    if (warnings.isEmpty() && failures.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (String warning : warnings()) {
      sb.append("- ").append(warning).append('\n');
    }
    if (!failures.isEmpty()) {
      sb.append(failures.size()).append("건 실패");
      for (Failure failure : failures().subList(0, Math.min(SAMPLE, failures.size()))) {
        sb.append("\n- ").append(failure.target()).append(": ").append(failure.message());
      }
    }
    return sb.toString().trim();
  }
}

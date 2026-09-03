package kr.hvy.blog.modules.stock.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 파이프라인 단계 실행 기록. 단계 실패는 다음 단계를 막지 않고(의존 관계는 호출부가 판단) 실패 목록·메타데이터에 남는다.
 */
@Slf4j
public final class PipelineSteps {

  private final CollectExecution execution;
  private final List<Map<String, Object>> steps = new ArrayList<>();

  public PipelineSteps(CollectExecution execution) {
    this.execution = execution;
  }

  /**
   * 단계 1개를 실행한다.
   *
   * @return 성공하면 true
   */
  public boolean run(String name, Runnable body) {
    if (execution.isCancelRequested()) {
      record(name, "CANCELED", 0, 0);
      return false;
    }
    long started = System.currentTimeMillis();
    int failuresBefore = execution.failureCount();
    long rowsBefore = execution.totalRows();
    try {
      body.run();
      record(name, "OK", System.currentTimeMillis() - started, execution.failureCount() - failuresBefore);
      log.info("파이프라인 단계 완료: step={}, rows=+{}, failures=+{}", name, execution.totalRows() - rowsBefore,
          execution.failureCount() - failuresBefore);
      return true;
    } catch (RuntimeException e) {
      execution.recordFailure("STEP:" + name, e.toString());
      record(name, "FAILED", System.currentTimeMillis() - started, execution.failureCount() - failuresBefore);
      log.error("파이프라인 단계 실패: step={}", name, e);
      return false;
    }
  }

  /**
   * 건너뛴 단계를 기록한다.
   */
  public void skip(String name, String reason) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("step", name);
    step.put("status", "SKIPPED");
    step.put("reason", reason);
    steps.add(step);
  }

  /**
   * 메타데이터에 단계 목록을 남긴다.
   */
  public void finish() {
    execution.putMetadata("steps", List.copyOf(steps));
  }

  private void record(String name, String status, long ms, int failures) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("step", name);
    step.put("status", status);
    step.put("ms", ms);
    step.put("failures", failures);
    steps.add(step);
  }
}

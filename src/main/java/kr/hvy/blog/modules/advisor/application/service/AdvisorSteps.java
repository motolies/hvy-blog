package kr.hvy.blog.modules.advisor.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잡 단계 실행 헬퍼. 단계 예외를 격리해 다음 단계로 진행하고 결과를 AdvisorExecution 에 남긴다 (stock 모듈 PipelineSteps 와 같은 역할).
 */
@Slf4j
@RequiredArgsConstructor
public final class AdvisorSteps {

  private final AdvisorExecution execution;

  /**
   * 단계를 실행한다. 예외는 삼키고 FAILED 로 기록한 뒤 false 를 돌려준다.
   */
  public boolean run(String name, Runnable body) {
    long started = System.currentTimeMillis();
    try {
      body.run();
      execution.recordStep(name, "OK", System.currentTimeMillis() - started);
      return true;
    } catch (RuntimeException e) {
      execution.recordStep(name, "FAILED", System.currentTimeMillis() - started);
      execution.recordFailure("STEP:" + name, e.toString());
      log.error("advisor 단계 실패(격리): runId={}, step={}", execution.runId(), name, e);
      return false;
    }
  }

  /**
   * 단계를 실행하되 실패하면 예외를 그대로 던진다 (이 단계 없이는 결과가 무의미할 때 — 예: 스크리닝·LLM 판단).
   */
  public void runOrThrow(String name, Runnable body) {
    long started = System.currentTimeMillis();
    try {
      body.run();
      execution.recordStep(name, "OK", System.currentTimeMillis() - started);
    } catch (RuntimeException e) {
      execution.recordStep(name, "FAILED", System.currentTimeMillis() - started);
      throw e;
    }
  }

  /**
   * 조건 미충족으로 건너뛴 단계를 기록한다.
   */
  public void skip(String name, String reason) {
    execution.recordStep(name, "SKIPPED", 0);
    execution.putMetadata("skip." + name, reason);
  }
}

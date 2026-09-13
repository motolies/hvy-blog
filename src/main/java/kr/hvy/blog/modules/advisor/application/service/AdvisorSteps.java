package kr.hvy.blog.modules.advisor.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잡 단계 실행 헬퍼. 단계 예외를 격리해 다음 단계로 진행하고 결과를 AdvisorExecution 에 남긴다 (stock 모듈 PipelineSteps 와 같은 역할).
 * <p>
 * 단계 경계마다 두 가지를 더 한다(2026-09-13): 시작 전 취소 요청을 확인해 {@link AdvisorCanceledException} 을 던지고(격리 catch 가 삼키지 않는다),
 * 기록 직후 {@link AdvisorExecution#flush()} 로 진행을 run 에 저장한다. IC 월 청크도 {@code run} 을 그대로 써서 같은 규칙을 받는다.
 */
@Slf4j
@RequiredArgsConstructor
public final class AdvisorSteps {

  private final AdvisorExecution execution;

  /**
   * 단계를 실행한다. 예외는 삼키고 FAILED 로 기록한 뒤 false 를 돌려준다. 취소 예외만은 CANCELED 로 기록하고 그대로 던진다.
   */
  public boolean run(String name, Runnable body) {
    throwIfCanceled(name);
    long started = System.currentTimeMillis();
    try {
      body.run();
      record(name, "OK", started);
      return true;
    } catch (AdvisorCanceledException e) {
      record(name, "CANCELED", started);
      throw e;
    } catch (RuntimeException e) {
      record(name, "FAILED", started);
      execution.recordFailure("STEP:" + name, e.toString());
      log.error("advisor 단계 실패(격리): runId={}, step={}", execution.runId(), name, e);
      return false;
    }
  }

  /**
   * 단계를 실행하되 실패하면 예외를 그대로 던진다 (이 단계 없이는 결과가 무의미할 때 — 예: 스크리닝·LLM 판단).
   */
  public void runOrThrow(String name, Runnable body) {
    throwIfCanceled(name);
    long started = System.currentTimeMillis();
    try {
      body.run();
      record(name, "OK", started);
    } catch (AdvisorCanceledException e) {
      record(name, "CANCELED", started);
      throw e;
    } catch (RuntimeException e) {
      record(name, "FAILED", started);
      throw e;
    }
  }

  /**
   * 조건 미충족으로 건너뛴 단계를 기록한다.
   */
  public void skip(String name, String reason) {
    execution.putMetadata("skip." + name, reason);
    record(name, "SKIPPED", System.currentTimeMillis());
  }

  /**
   * 취소가 요청됐으면 이 단계를 CANCELED 로 남기고 던진다 — 청크·단계 경계가 협조적 취소의 감지 지점이다.
   */
  private void throwIfCanceled(String name) {
    if (execution.checkCancelNow()) {
      record(name, "CANCELED", System.currentTimeMillis());
      throw new AdvisorCanceledException(execution.runId(), name);
    }
  }

  /**
   * 단계 결과를 기록하고 진행을 run 에 저장한다.
   */
  private void record(String name, String status, long started) {
    execution.recordStep(name, status, System.currentTimeMillis() - started);
    execution.flush();
  }
}

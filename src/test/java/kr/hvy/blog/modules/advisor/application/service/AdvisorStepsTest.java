package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 단계 실행기의 진행 기록·취소 규칙(순수): 단계마다 flush 1회, 취소는 다음 단계 경계에서 AdvisorCanceledException 으로 전파(격리 catch 가 삼키지 않음),
 * 미바인딩 execution 은 flush·취소 모두 no-op.
 */
class AdvisorStepsTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());

  @Test
  @DisplayName("바인딩된 flush 는 run·runOrThrow·skip·실패 단계마다 1회 불린다")
  void flushesAfterEveryStep() {
    AdvisorExecution execution = execution();
    AtomicInteger flushes = new AtomicInteger();
    execution.bind(flushes::incrementAndGet, () -> false);
    AdvisorSteps steps = new AdvisorSteps(execution);

    assertThat(steps.run("A", () -> { })).isTrue();
    steps.runOrThrow("B", () -> { });
    steps.skip("C", "이유");
    assertThat(steps.run("D", () -> {
      throw new IllegalStateException("boom");
    })).isFalse();

    assertThat(flushes.get()).isEqualTo(4);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::status).containsExactly("OK", "OK", "SKIPPED", "FAILED");
    assertThat(execution.isCancelRequested()).isFalse();
  }

  @Test
  @DisplayName("취소 요청은 다음 단계 경계에서 CANCELED 기록 + 예외 전파, 중첩(IC 청크) 단계에서도 바깥 run() 이 삼키지 않는다")
  void cancelPropagatesAtStepBoundary() {
    AdvisorExecution execution = execution();
    AtomicBoolean cancel = new AtomicBoolean(false);
    execution.bind(() -> { }, cancel::get);
    AdvisorSteps steps = new AdvisorSteps(execution);

    assertThat(steps.run("A", () -> cancel.set(true))).as("실행 중 단계는 끝까지 간다").isTrue();
    assertThatThrownBy(() -> steps.run("B", () -> { })).isInstanceOf(AdvisorCanceledException.class).hasMessageContaining("B");
    assertThat(execution.isCancelRequested()).isTrue();
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name).containsExactly("A", "B");
    assertThat(execution.steps().getLast().status()).isEqualTo("CANCELED");

    AdvisorExecution nested = execution();
    AtomicBoolean cancel2 = new AtomicBoolean(false);
    nested.bind(() -> { }, cancel2::get);
    AdvisorSteps nestedSteps = new AdvisorSteps(nested);
    assertThatThrownBy(() -> nestedSteps.run("OUTER", () -> {
      cancel2.set(true);
      nestedSteps.run("INNER", () -> { });
    })).isInstanceOf(AdvisorCanceledException.class);
    assertThat(nested.steps()).extracting(AdvisorExecution.StepResult::name).containsExactly("INNER", "OUTER");
    assertThat(nested.steps()).extracting(AdvisorExecution.StepResult::status).containsOnly("CANCELED");
    assertThat(nested.failures()).as("취소는 실패로 기록하지 않는다").isEmpty();
  }

  @Test
  @DisplayName("flush 예외는 삼키고, 미바인딩 execution 은 flush·취소 모두 no-op")
  void flushErrorsSwallowedAndUnboundIsNoop() {
    AdvisorExecution bound = execution();
    bound.bind(() -> {
      throw new IllegalStateException("db down");
    }, () -> false);
    assertThat(new AdvisorSteps(bound).run("A", () -> { })).isTrue();

    AdvisorExecution unbound = execution();
    AdvisorSteps steps = new AdvisorSteps(unbound);
    assertThat(steps.run("A", () -> { })).isTrue();
    steps.runOrThrow("B", () -> { });
    assertThat(unbound.isCancelRequested()).isFalse();
    assertThat(unbound.checkCancelNow()).isFalse();
    assertThat(unbound.steps()).hasSize(2);
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(7L).jobType(AdvisorJobType.ADVISE).triggerType(AdvisorTriggerType.API)
        .baseDate(LocalDate.of(2026, 9, 4)).build();
    return new AdvisorExecution(run, LocalDate.of(2026, 9, 4), properties);
  }
}

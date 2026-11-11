package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.mock.env.MockEnvironment;

/**
 * LLM 사용량·비용 누적. 공급자 폴백(Claude 1순위 → OpenAI, 2026-11-11)으로 한 run 안에 공급자가 섞일 수 있어 비용은 호출마다 그 공급자 단가로 계산해 더한다.
 */
@DisplayName("AdvisorExecution - LLM 사용량·공급자별 비용 누적")
class AdvisorExecutionTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final LocalDate base = LocalDate.of(2026, 11, 11);

  @BeforeEach
  void setUp() {
    // OpenAI: 입력 1 · 출력 10 / Claude: 입력 4 · 출력 20 · 캐시 읽기 0.2 (100만 토큰당 USD)
    properties.getCost().setInputPer1mUsd(new BigDecimal("1"));
    properties.getCost().setOutputPer1mUsd(new BigDecimal("10"));
    properties.getClaude().getCost().setInputPer1mUsd(new BigDecimal("4"));
    properties.getClaude().getCost().setOutputPer1mUsd(new BigDecimal("20"));
    properties.getClaude().getCost().setCacheReadPer1mUsd(new BigDecimal("0.2"));
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(1L).jobType(AdvisorJobType.ADVISE).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }

  @Test
  @DisplayName("공급자가 섞인 run 은 호출마다 그 공급자 단가로 계산해 더한다 — 합계 토큰 × 단일 단가가 아니다. 공급자 표시가 없으면(null) OpenAI 단가")
  void 공급자별_비용_누적() {
    AdvisorExecution execution = execution();

    // Claude: 4 × 0.1M + 0.2 × 0.9M + 20 × 0.5M = 10.58
    execution.recordLlmUsage(LlmProvider.CLAUDE, "claude-x", "v1", new DefaultUsage(1_000_000, 500_000), 100, 900_000);
    assertThat(execution.costUsd()).isEqualByComparingTo("10.58");
    // OpenAI(폴백): 1 × 1M + 10 × 0.5M = 6 (캐시 할인 없음 — 기존 식)
    execution.recordLlmUsage(LlmProvider.OPENAI, "gpt-x", "v1", new DefaultUsage(1_000_000, 500_000), 50, 100);
    assertThat(execution.costUsd()).isEqualByComparingTo("16.58");
    // 공급자 표시 없는 응답: OpenAI 단가 1 × 1M = 1
    execution.recordLlmUsage(null, "stub", "v2", new DefaultUsage(1_000_000, 0), 0, 0);

    assertThat(execution.costUsd()).isEqualByComparingTo("17.58");
    assertThat(execution.llmCalls()).isEqualTo(3);
    assertThat(execution.promptTokens()).isEqualTo(3_000_000);
    assertThat(execution.completionTokens()).isEqualTo(1_000_000);
    assertThat(execution.reasoningTokens()).isEqualTo(150);
    assertThat(execution.cachedTokens()).isEqualTo(900_100);
    assertThat(execution.model()).as("마지막 호출의 모델").isEqualTo("stub");
    assertThat(execution.promptVersion()).isEqualTo("v2");
    assertThat(execution.metadataSnapshot()).containsEntry("llmCalls", 3).containsEntry("promptTokens", 3_000_000);
  }

  @Test
  @DisplayName("OpenAI 만 쓰는 run 의 비용은 기존 식(합계 토큰 × advisor.cost 단가)과 같다")
  void OpenAI만_기존식과_동일() {
    AdvisorExecution execution = execution();

    execution.recordLlmUsage(LlmProvider.OPENAI, "gpt-x", "v1", new DefaultUsage(6000, 1900), 300, 100);
    execution.recordLlmUsage(null, "gpt-x", "v1", new DefaultUsage(4000, 100), 0, 0);

    // (1 × 10000 + 10 × 2000) / 1M = 0.03
    assertThat(execution.costUsd()).isEqualByComparingTo("0.03");
  }

  @Test
  @DisplayName("단가가 0 이면 비용 0, usage 가 없어도(null) 호출 수는 센다. 호출이 없으면 0")
  void 단가0_usage없음() {
    AdvisorProperties free = new AdvisorProperties(new MockEnvironment());
    AdvisorRun run = AdvisorRun.builder().runId(2L).jobType(AdvisorJobType.ADVISE).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    AdvisorExecution execution = new AdvisorExecution(run, base, free);
    assertThat(execution.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);

    execution.recordLlmUsage(LlmProvider.CLAUDE, "claude-x", "v1", new DefaultUsage(1_000_000, 1_000_000), 0, 0);
    execution.recordLlmUsage(LlmProvider.OPENAI, "gpt-x", "v1", null, 7, 3);

    assertThat(execution.costUsd().signum()).isZero();
    assertThat(execution.llmCalls()).isEqualTo(2);
    assertThat(execution.promptTokens()).isEqualTo(1_000_000);
    assertThat(execution.reasoningTokens()).isEqualTo(7);
    assertThat(execution.cachedTokens()).isEqualTo(3);
  }

  @Test
  @DisplayName("여러 스레드가 동시에 기록해도 비용·토큰 누적이 유실되지 않는다(기존 카운터와 같은 원자적 누적)")
  void 동시_기록() throws InterruptedException {
    AdvisorExecution execution = execution();
    int threads = 8;
    int callsPerThread = 500;
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      LlmProvider provider = t % 2 == 0 ? LlmProvider.CLAUDE : LlmProvider.OPENAI;
      workers.add(Thread.ofPlatform().unstarted(() -> {
        for (int i = 0; i < callsPerThread; i++) {
          execution.recordLlmUsage(provider, "m", "v", new DefaultUsage(1000, 100), 1, 0);
        }
      }));
    }
    workers.forEach(Thread::start);
    for (Thread worker : workers) {
      worker.join();
    }

    // Claude 1건 = (4 × 1000 + 20 × 100) / 1M = 0.006, OpenAI 1건 = (1 × 1000 + 10 × 100) / 1M = 0.002 → 2000건씩
    assertThat(execution.llmCalls()).isEqualTo(threads * callsPerThread);
    assertThat(execution.promptTokens()).isEqualTo(threads * callsPerThread * 1000);
    assertThat(execution.costUsd()).isEqualByComparingTo("16");
  }
}

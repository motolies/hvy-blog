package kr.hvy.blog.modules.advisor.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * advisor.claude.* 와 공급자별 준비 상태·단가 계산. 폴백 래퍼가 호출마다 준비 상태를 묻고, 잡·채팅이 이 식으로 비용을 기록한다.
 */
class AdvisorClaudePropertiesTest {

  @Test
  @DisplayName("Claude 준비 상태: 스위치·키·모델 ID 가 모두 있어야 한다. 키는 호출 시점에 Environment 에서 읽는다")
  void claudeReady() {
    MockEnvironment env = new MockEnvironment();
    AdvisorProperties p = new AdvisorProperties(env);

    assertThat(p.anthropicApiKey()).isEmpty();
    assertThat(p.claudeReady("claude-x")).as("키 없음").isFalse();

    env.setProperty("spring.ai.anthropic.api-key", "sk-test");
    assertThat(p.claudeReady("claude-x")).isTrue();
    assertThat(p.claudeReady(" ")).as("모델 ID 없음").isFalse();

    p.getClaude().setEnabled(false);
    assertThat(p.claudeReady("claude-x")).as("롤백 스위치").isFalse();
  }

  @Test
  @DisplayName("OpenAI 준비 상태: 키·모델 ID. Claude 스위치와 무관하다")
  void openAiReady() {
    MockEnvironment env = new MockEnvironment().withProperty("spring.ai.openai.api-key", "sk-openai");
    AdvisorProperties p = new AdvisorProperties(env);
    p.getClaude().setEnabled(false);

    assertThat(p.openAiReady("gpt-x")).isTrue();
    assertThat(p.openAiReady(null)).isFalse();
    assertThat(new AdvisorProperties(new MockEnvironment()).openAiReady("gpt-x")).isFalse();
  }

  @Test
  @DisplayName("기본값: Claude 켜짐, 재시도 2·타임아웃 300초(채팅 90초), 차단 60/5/30분. 채팅 모델이 비면 assist")
  void defaults() {
    AdvisorProperties.Claude claude = new AdvisorProperties(new MockEnvironment()).getClaude();

    assertThat(claude.isEnabled()).isTrue();
    assertThat(claude.getMaxRetries()).isEqualTo(2);
    assertThat(claude.getTimeoutSeconds()).isEqualTo(300);
    assertThat(claude.getChatTimeoutSeconds()).as("채팅의 도구 마감(advisor.chat.answer-timeout-seconds 180초)보다 충분히 짧다").isEqualTo(90);
    assertThat(claude.getJudgeMaxTokens()).isEqualTo(16_000);
    assertThat(claude.getCooldown().getExhaustedMinutes()).isEqualTo(60);
    assertThat(claude.getCooldown().getUnavailableMinutes()).isEqualTo(5);
    assertThat(claude.getCooldown().getMisconfiguredMinutes()).isEqualTo(30);

    claude.setAssist("claude-assist");
    assertThat(claude.chatOrAssist()).isEqualTo("claude-assist");
    claude.setChat("claude-chat");
    assertThat(claude.chatOrAssist()).isEqualTo("claude-chat");
  }

  @Test
  @DisplayName("비용: OpenAI(·공급자 미표시)는 입력·출력 단가 한 쌍, Claude 는 캐시 읽기 토큰을 따로 매긴다")
  void costUsd() {
    AdvisorProperties p = new AdvisorProperties(new MockEnvironment());
    assertThat(p.costUsd(LlmProvider.CLAUDE, 1_000_000, 1_000_000, 0)).as("단가 0 = 미계산").isEqualByComparingTo(BigDecimal.ZERO);

    p.getCost().setInputPer1mUsd(new BigDecimal("1"));
    p.getCost().setOutputPer1mUsd(new BigDecimal("10"));
    p.getClaude().getCost().setInputPer1mUsd(new BigDecimal("4"));
    p.getClaude().getCost().setOutputPer1mUsd(new BigDecimal("20"));

    // OpenAI: 1 × 1M + 10 × 0.5M = 6 (캐시 무시 — 기존 식)
    assertThat(p.costUsd(LlmProvider.OPENAI, 1_000_000, 500_000, 900_000)).isEqualByComparingTo("6");
    assertThat(p.costUsd(null, 1_000_000, 500_000, 0)).as("공급자 표시가 없으면 OpenAI 단가").isEqualByComparingTo("6");

    // Claude, cache-read 미설정: 캐시 토큰도 입력 단가 → 4 × 1M + 20 × 0.5M = 14
    assertThat(p.costUsd(LlmProvider.CLAUDE, 1_000_000, 500_000, 900_000)).isEqualByComparingTo("14");

    // Claude, cache-read 0.2: 4 × 0.1M + 0.2 × 0.9M + 20 × 0.5M = 0.4 + 0.18 + 10 = 10.58
    p.getClaude().getCost().setCacheReadPer1mUsd(new BigDecimal("0.2"));
    assertThat(p.costUsd(LlmProvider.CLAUDE, 1_000_000, 500_000, 900_000)).isEqualByComparingTo("10.58");

    // 캐시 토큰이 입력 합보다 크게 들어와도 음수가 되지 않는다
    assertThat(p.costUsd(LlmProvider.CLAUDE, 100, 0, 500)).isEqualByComparingTo(new BigDecimal("0.00002"));
  }
}

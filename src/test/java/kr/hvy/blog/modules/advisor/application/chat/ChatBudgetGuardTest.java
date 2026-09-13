package kr.hvy.blog.modules.advisor.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.repository.jdbc.ChatWriter;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

class ChatBudgetGuardTest {

  private AdvisorChatProperties properties;
  private ChatWriter writer;
  private RedissonClient redisson;
  private RBucket<Object> bucket;
  private ChatBudgetGuard guard;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    properties = mock(AdvisorChatProperties.class);
    when(properties.getDailyTokenBudget()).thenReturn(1_000L);
    when(properties.getPerUserCooldownSeconds()).thenReturn(20);
    writer = mock(ChatWriter.class);
    when(writer.tokensSince(any())).thenReturn(0L);
    redisson = mock(RedissonClient.class);
    bucket = mock(RBucket.class);
    when(redisson.getBucket(anyString())).thenReturn(bucket);
    when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(true);
    guard = new ChatBudgetGuard(properties, writer, redisson);
  }

  static IncomingQuestion question() {
    return new IncomingQuestion("Ev", "C1", "U1", null, "1.0", null, "q");
  }

  @Test
  @DisplayName("예산 직전이면 통과, 도달하면 BUDGET 거부이고 쿨다운 키는 심지 않는다")
  void dailyBudget() {
    when(writer.tokensSince(any())).thenReturn(999L);
    assertThat(guard.check(question())).isEmpty();
    verify(bucket, times(1)).setIfAbsent(any(), any(Duration.class));   // 통과 → 쿨다운 키 1회

    when(writer.tokensSince(any())).thenReturn(1_000L);
    Optional<ChatBudgetGuard.Refusal> refusal = guard.check(question());
    assertThat(refusal).isPresent();
    assertThat(refusal.get().code()).isEqualTo("BUDGET");
    assertThat(refusal.get().message()).contains("1,000");
    verify(bucket, times(1)).setIfAbsent(any(), any(Duration.class));   // 거부 → 추가로 심지 않는다
  }

  @Test
  @DisplayName("예산 0 은 무제한이라 합계를 조회하지 않는다")
  void zeroBudgetIsUnlimited() {
    when(properties.getDailyTokenBudget()).thenReturn(0L);
    assertThat(guard.check(question())).isEmpty();
    verify(writer, never()).tokensSince(any());
  }

  @Test
  @DisplayName("예산 기준 시각은 KST 자정이다")
  void budgetWindowStartsAtKstMidnight() {
    guard.check(question());
    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(writer).tokensSince(since.capture());
    assertThat(since.getValue()).isEqualTo(LocalDate.now(MarketClock.KST).atStartOfDay(MarketClock.KST).toInstant());
  }

  @Test
  @DisplayName("쿨다운 — 키가 이미 있으면 남은 초와 함께 거부, 없으면 심고 통과")
  void cooldown() {
    assertThat(guard.check(question())).isEmpty();
    verify(bucket).setIfAbsent("1", Duration.ofSeconds(20));

    when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(false);
    when(bucket.remainTimeToLive()).thenReturn(7_400L);
    Optional<ChatBudgetGuard.Refusal> refusal = guard.check(question());
    assertThat(refusal).isPresent();
    assertThat(refusal.get().code()).isEqualTo("COOLDOWN");
    assertThat(refusal.get().message()).contains("7초");
  }

  @Test
  @DisplayName("쿨다운 0 이면 Redis 를 건드리지 않는다")
  void zeroCooldownSkipsRedis() {
    when(properties.getPerUserCooldownSeconds()).thenReturn(0);
    assertThat(guard.check(question())).isEmpty();
    verify(redisson, never()).getBucket(anyString());
  }

  @Test
  @DisplayName("DB·Redis 장애는 통과시킨다(방어선이 봇을 멈추면 안 된다)")
  void failuresPass() {
    when(writer.tokensSince(any())).thenThrow(new IllegalStateException("db down"));
    when(redisson.getBucket(anyString())).thenThrow(new IllegalStateException("redis down"));
    assertThat(guard.check(question())).isEmpty();
  }
}

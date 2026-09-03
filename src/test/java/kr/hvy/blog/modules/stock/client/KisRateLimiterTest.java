package kr.hvy.blog.modules.stock.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 고정 윈도우 리미터 동작 — 창당 허용 수 초과 시 블로킹, EGW00201 적응 하향과 연속 성공 원복.
 */
class KisRateLimiterTest {

  private static KisProperties properties(int windowMs, int permits, int recoveryStreak) {
    KisProperties properties = new KisProperties();
    properties.getRateLimit().setWindowMs(windowMs);
    properties.getRateLimit().setPermits(permits);
    properties.getRateLimit().setRecoveryStreak(recoveryStreak);
    return properties;
  }

  @Test
  @DisplayName("창당 허용 수를 넘는 acquire 는 다음 창까지 대기한다")
  void acquireBlocksBeyondWindowPermits() {
    KisRateLimiter limiter = new KisRateLimiter(properties(200, 3, 200));
    try {
      long start = System.nanoTime();
      for (int i = 0; i < 7; i++) {
        limiter.acquire(); // 3건 즉시, 4~6번째는 두 번째 창, 7번째는 세 번째 창 → 최소 2회 대기(약 400ms)
      }
      long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
      assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
    } finally {
      limiter.shutdown();
    }
  }

  @Test
  @DisplayName("한도 초과 응답은 허용 수를 낮추고, 연속 성공이 임계에 닿으면 한 단계씩 원복한다")
  void adaptiveLimit() {
    KisRateLimiter limiter = new KisRateLimiter(properties(200, 3, 5));
    try {
      assertThat(limiter.currentPermits()).isEqualTo(3);

      limiter.onRateLimited();
      assertThat(limiter.currentPermits()).isEqualTo(2);
      limiter.onRateLimited();
      assertThat(limiter.currentPermits()).isEqualTo(1);
      limiter.onRateLimited(); // 1 아래로는 내려가지 않는다
      assertThat(limiter.currentPermits()).isEqualTo(1);

      for (int i = 0; i < 5; i++) {
        limiter.onSuccess();
      }
      assertThat(limiter.currentPermits()).isEqualTo(2);
      for (int i = 0; i < 5; i++) {
        limiter.onSuccess();
      }
      assertThat(limiter.currentPermits()).isEqualTo(3);
      for (int i = 0; i < 5; i++) {
        limiter.onSuccess(); // 기준값 위로는 올라가지 않는다
      }
      assertThat(limiter.currentPermits()).isEqualTo(3);
    } finally {
      limiter.shutdown();
    }
  }
}

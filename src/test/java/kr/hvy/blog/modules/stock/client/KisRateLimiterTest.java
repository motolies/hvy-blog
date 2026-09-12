package kr.hvy.blog.modules.stock.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 균등 간격 리미터 — 호출은 최소 간격으로 분산되고, EGW00201 은 짧은 창에 몰릴 때만 간격을 늘리며 연속 성공이 되돌린다.
 * 2026-09-09 로그(응답 1건마다 하향 → 4시간 저속)를 재현하지 않는 것이 핵심이다.
 */
class KisRateLimiterTest {

  private static KisProperties properties(int minMs, int maxMs, int burstThreshold, int burstWindowMs, int recoveryStreak) {
    KisProperties properties = new KisProperties();
    properties.getRateLimit().setMinIntervalMs(minMs);
    properties.getRateLimit().setMaxIntervalMs(maxMs);
    properties.getRateLimit().setBackoffFactor(1.5);
    properties.getRateLimit().setBurstThreshold(burstThreshold);
    properties.getRateLimit().setBurstWindowMs(burstWindowMs);
    properties.getRateLimit().setRecoveryStreak(recoveryStreak);
    return properties;
  }

  @Test
  @DisplayName("연속 acquire 는 최소 간격만큼 떨어져 나간다 (첫 호출 즉시, 이후 간격마다)")
  void acquireSpacesCallsEvenly() {
    KisRateLimiter limiter = new KisRateLimiter(properties(50, 500, 3, 10_000, 200));
    long start = System.nanoTime();
    for (int i = 0; i < 4; i++) {
      limiter.acquire();
    }
    long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
    assertThat(elapsedMs).isGreaterThanOrEqualTo(150); // 0, 50, 100, 150
  }

  @Test
  @DisplayName("단발 EGW00201 은 간격을 바꾸지 않는다 (재시도가 흡수)")
  void singleHitDoesNotWiden() {
    KisRateLimiter limiter = new KisRateLimiter(properties(67, 500, 3, 10_000, 200));

    limiter.onRateLimited();
    limiter.onRateLimited();

    assertThat(limiter.currentIntervalMs()).isEqualTo(67);
  }

  @Test
  @DisplayName("창 안에 임계 이상 몰리면 간격을 1.5배씩 늘리고(상한 캡), 연속 성공이 임계에 닿을 때마다 되돌리되 기준 아래로는 내려가지 않는다")
  void burstWidensAndRecovers() {
    KisRateLimiter limiter = new KisRateLimiter(properties(60, 200, 3, 10_000, 5));

    hit(limiter, 3);
    assertThat(limiter.currentIntervalMs()).isEqualTo(90);
    hit(limiter, 3);
    assertThat(limiter.currentIntervalMs()).isEqualTo(135);
    hit(limiter, 3);
    assertThat(limiter.currentIntervalMs()).isEqualTo(200); // 202.5 → 상한 200
    hit(limiter, 3);
    assertThat(limiter.currentIntervalMs()).isEqualTo(200);

    succeed(limiter, 5);
    assertThat(limiter.currentIntervalMs()).isEqualTo(133);
    succeed(limiter, 5);
    assertThat(limiter.currentIntervalMs()).isEqualTo(88);
    succeed(limiter, 5);
    assertThat(limiter.currentIntervalMs()).isEqualTo(60);
    succeed(limiter, 5);
    assertThat(limiter.currentIntervalMs()).isEqualTo(60);
  }

  @Test
  @DisplayName("버스트 창 밖으로 흩어진 적중은 누적되지 않는다")
  void hitsOutsideWindowDoNotAccumulate() throws InterruptedException {
    KisRateLimiter limiter = new KisRateLimiter(properties(60, 200, 3, 40, 5));

    for (int i = 0; i < 3; i++) {
      limiter.onRateLimited();
      Thread.sleep(60);
    }

    assertThat(limiter.currentIntervalMs()).isEqualTo(60);
  }

  @Test
  @DisplayName("여러 스레드가 동시에 acquire 해도 슬롯이 겹치지 않아 전체가 간격 × (호출 수 − 1) 이상 걸린다")
  void concurrentAcquiresNeverShareSlot() throws Exception {
    KisRateLimiter limiter = new KisRateLimiter(properties(20, 200, 3, 10_000, 200));
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      long start = System.nanoTime();
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 3; t++) {
        futures.add(pool.submit(() -> {
          for (int i = 0; i < 3; i++) {
            limiter.acquire();
          }
        }));
      }
      for (Future<?> future : futures) {
        future.get();
      }
      long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
      assertThat(elapsedMs).isGreaterThanOrEqualTo(150); // 9회 호출 → 마지막 슬롯은 8 × 20ms 이후
    } finally {
      pool.shutdownNow();
    }
  }

  private static void hit(KisRateLimiter limiter, int times) {
    for (int i = 0; i < times; i++) {
      limiter.onRateLimited();
    }
  }

  private static void succeed(KisRateLimiter limiter, int times) {
    for (int i = 0; i < times; i++) {
      limiter.onSuccess();
    }
  }
}

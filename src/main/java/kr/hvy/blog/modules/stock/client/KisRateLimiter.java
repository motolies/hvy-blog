package kr.hvy.blog.modules.stock.client;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KIS 초당 호출 한도(실전 약 20건/초) 준수용 프로세스 내 레이트 리미터 — 호출을 균등 간격으로 내보낸다.
 * <p>
 * 2026-09-09 이전엔 200ms 창 × 3건(Commons TimedSemaphore)이었는데, 창이 열릴 때 3건이 동시에 나가는 버스트 탓인지 EGW00201 이
 * 허용량(3·2·1)과 무관하게 호출의 약 0.3% 로 일정하게 왔고, 응답 1건마다 한 단계씩 낮추는 바람에 4시간 내내 5~10건/초로 돌았다(하향 270회).
 * 지금은 호출 사이 최소 간격(기본 67ms ≈ 15건/초)을 슬롯 예약으로 보장해 임의의 1초 창에 15건을 넘기지 않고,
 * EGW00201 은 짧은 창 안에 몰릴 때만 간격을 늘리며(단발은 {@link KisApiClient} 의 재시도가 흡수) 연속 성공이 쌓이면 되돌린다.
 * 백필은 단일 인스턴스에서만 돌므로 분산 리미터는 두지 않는다.
 */
@Slf4j
@Component
public class KisRateLimiter {

  private static final long NANOS_PER_MILLI = 1_000_000L;

  private final long baseIntervalNanos;
  private final long maxIntervalNanos;
  private final double backoffFactor;
  private final int burstThreshold;
  private final long burstWindowNanos;
  private final int recoveryStreak;

  private final Object slotLock = new Object();
  /** 다음 호출이 나갈 수 있는 가장 이른 시각(nanoTime). slotLock 으로 보호 */
  private long nextSlotNanos;

  private final Object adjustLock = new Object();
  /** 최근 EGW00201 수신 시각. adjustLock 으로 보호 */
  private final Deque<Long> recentHits = new ArrayDeque<>();
  private final AtomicInteger successStreak = new AtomicInteger();
  private volatile long currentIntervalNanos;

  public KisRateLimiter(KisProperties properties) {
    KisProperties.RateLimit config = properties.getRateLimit();
    this.baseIntervalNanos = Math.max(1, config.getMinIntervalMs()) * NANOS_PER_MILLI;
    this.maxIntervalNanos = Math.max(baseIntervalNanos, config.getMaxIntervalMs() * NANOS_PER_MILLI);
    this.backoffFactor = Math.max(1.01, config.getBackoffFactor());
    this.burstThreshold = Math.max(1, config.getBurstThreshold());
    this.burstWindowNanos = Math.max(1, config.getBurstWindowMs()) * NANOS_PER_MILLI;
    this.recoveryStreak = Math.max(1, config.getRecoveryStreak());
    this.currentIntervalNanos = baseIntervalNanos;
    this.nextSlotNanos = System.nanoTime();
  }

  /**
   * 다음 호출 슬롯을 예약하고 그 시각까지 기다린다. 예약은 락 안에서 즉시 끝나므로 동시 호출도 슬롯이 겹치지 않고
   * 현재 간격만큼 떨어져 나간다. 인터럽트는 잡 중단으로 전파한다.
   */
  public void acquire() {
    long slot;
    synchronized (slotLock) {
      long now = System.nanoTime();
      slot = Math.max(now, nextSlotNanos);
      nextSlotNanos = slot + currentIntervalNanos;
    }
    long remaining = slot - System.nanoTime();
    if (remaining <= 0) {
      return;
    }
    try {
      Thread.sleep(Duration.ofNanos(remaining));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KisApiException(null, null, null, null, false, "KIS 레이트 리미터 대기 중 인터럽트", e);
    }
  }

  /**
   * EGW00201(초당 한도 초과) 수신. burst-window 안에 burst-threshold 이상 몰릴 때만 간격을 backoff-factor 배 늘린다(상한 max-interval).
   * 단발 적중은 재시도(1초 대기)가 흡수하므로 DEBUG 만 남긴다 — 예전처럼 응답 1건마다 낮추면 저속이 오래 간다.
   */
  public void onRateLimited() {
    synchronized (adjustLock) {
      long now = System.nanoTime();
      recentHits.addLast(now);
      while (!recentHits.isEmpty() && now - recentHits.peekFirst() > burstWindowNanos) {
        recentHits.pollFirst();
      }
      successStreak.set(0);
      if (recentHits.size() < burstThreshold) {
        log.debug("KIS 초당 한도 초과 응답 (단발, 재시도가 흡수): 최근 {}ms 안 {}회, 간격 {}ms",
            burstWindowNanos / NANOS_PER_MILLI, recentHits.size(), currentIntervalMs());
        return;
      }
      long widened = Math.min(maxIntervalNanos, (long) (currentIntervalNanos * backoffFactor));
      if (widened > currentIntervalNanos) {
        log.info("KIS 초당 한도 초과 {}회/{}ms → 호출 간격 확대: {}ms → {}ms", recentHits.size(),
            burstWindowNanos / NANOS_PER_MILLI, currentIntervalMs(), widened / NANOS_PER_MILLI);
        currentIntervalNanos = widened;
      }
      recentHits.clear();
    }
  }

  /**
   * 성공 호출을 기록한다. 늘어난 간격은 연속 성공이 임계에 닿을 때마다 backoff-factor 로 나눠 기준 간격까지 되돌린다.
   */
  public void onSuccess() {
    if (currentIntervalNanos <= baseIntervalNanos) {
      return;
    }
    if (successStreak.incrementAndGet() >= recoveryStreak) {
      synchronized (adjustLock) {
        if (currentIntervalNanos > baseIntervalNanos) {
          long narrowed = Math.max(baseIntervalNanos, (long) (currentIntervalNanos / backoffFactor));
          log.info("KIS 연속 성공 {}회 → 호출 간격 원복: {}ms → {}ms", recoveryStreak, currentIntervalMs(), narrowed / NANOS_PER_MILLI);
          currentIntervalNanos = narrowed;
        }
        successStreak.set(0);
      }
    }
  }

  /**
   * 현재 호출 간격(ms, 내림).
   */
  public long currentIntervalMs() {
    return currentIntervalNanos / NANOS_PER_MILLI;
  }
}

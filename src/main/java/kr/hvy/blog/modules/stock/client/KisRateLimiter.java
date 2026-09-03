package kr.hvy.blog.modules.stock.client;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.TimedSemaphore;
import org.springframework.stereotype.Component;

/**
 * KIS 초당 호출 한도(실전 약 20건/초) 준수용 프로세스 내 레이트 리미터.
 * <p>
 * 기존 {@code TimedSemaphoreHandler} 는 세마포어를 키별로 캐싱해 한도를 나중에 바꿀 수 없으므로 쓰지 않는다.
 * 200ms 창 × 3건(=15건/초)으로 잘게 쪼개 슬라이딩 1초 창의 최댓값을 18건 아래로 묶고,
 * EGW00201 을 받으면 한도를 한 단계 낮춘 뒤 연속 성공이 쌓이면 원복한다(적응 제어).
 * 백필은 단일 인스턴스에서만 돌므로 분산 리미터는 두지 않는다.
 */
@Slf4j
@Component
public class KisRateLimiter {

  private final TimedSemaphore semaphore;
  private final int basePermits;
  private final int recoveryStreak;
  private final AtomicInteger successStreak = new AtomicInteger();
  private final Object adjustLock = new Object();
  private volatile int currentPermits;

  public KisRateLimiter(KisProperties properties) {
    KisProperties.RateLimit config = properties.getRateLimit();
    this.basePermits = Math.max(1, config.getPermits());
    this.recoveryStreak = Math.max(1, config.getRecoveryStreak());
    this.currentPermits = basePermits;
    this.semaphore = new TimedSemaphore(Math.max(1, config.getWindowMs()), TimeUnit.MILLISECONDS, basePermits);
  }

  /**
   * 호출 슬롯을 확보한다. 창이 가득 차면 다음 창까지 블로킹한다. 인터럽트는 잡 중단으로 전파한다.
   */
  public void acquire() {
    try {
      semaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KisApiException(null, null, null, null, false, "KIS 레이트 리미터 대기 중 인터럽트", e);
    }
  }

  /**
   * EGW00201(초당 한도 초과) 수신 시 한도를 한 단계 낮춘다.
   */
  public void onRateLimited() {
    synchronized (adjustLock) {
      successStreak.set(0);
      if (currentPermits > 1) {
        currentPermits--;
        semaphore.setLimit(currentPermits);
        log.warn("KIS 초당 한도 초과 응답 → 창당 허용 호출 수 하향: {} → {}", currentPermits + 1, currentPermits);
      }
    }
  }

  /**
   * 성공 호출을 기록한다. 낮춰진 한도는 연속 성공이 임계에 도달하면 한 단계씩 원복한다.
   */
  public void onSuccess() {
    if (currentPermits >= basePermits) {
      return;
    }
    if (successStreak.incrementAndGet() >= recoveryStreak) {
      synchronized (adjustLock) {
        if (currentPermits < basePermits) {
          currentPermits++;
          semaphore.setLimit(currentPermits);
          log.info("KIS 연속 성공 {}회 → 창당 허용 호출 수 원복: {}", recoveryStreak, currentPermits);
        }
        successStreak.set(0);
      }
    }
  }

  public int currentPermits() {
    return currentPermits;
  }

  /**
   * 세마포어의 타이머 스레드를 정리한다.
   */
  @PreDestroy
  public void shutdown() {
    semaphore.shutdown();
  }
}

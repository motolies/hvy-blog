package kr.hvy.blog.modules.stock.client;

import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;

/**
 * 한 수집 실행(run) 안에서 KIS 호출 통계를 스레드 안전하게 누적한다.
 * 서비스가 주기적으로 스냅샷을 떠 run 카운터에 반영한다.
 */
@Getter
public final class KisCallStats {

  private final AtomicLong apiCalls = new AtomicLong();
  private final AtomicLong apiFails = new AtomicLong();
  private final AtomicLong rateLimitHits = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();

  /**
   * 누적값을 0 으로 되돌리고 그때까지의 값을 돌려준다. run 카운터 flush 에 쓴다.
   */
  public Snapshot drain() {
    return new Snapshot(apiCalls.getAndSet(0), apiFails.getAndSet(0), rateLimitHits.getAndSet(0), retries.getAndSet(0));
  }

  public record Snapshot(long apiCalls, long apiFails, long rateLimitHits, long retries) {
  }
}

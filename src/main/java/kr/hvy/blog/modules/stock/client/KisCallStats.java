package kr.hvy.blog.modules.stock.client;

import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;

/**
 * 한 수집 실행(run) 안에서 KIS 호출 통계를 스레드 안전하게 누적한다.
 * <p>
 * {@code apiCalls}/{@code apiFails} 는 run 카운터로 영속되므로 {@link #drain()} 으로 비워 가며 반영한다.
 * {@code rateLimitHits}/{@code retries} 는 DB 컬럼이 없는 run 전체 누적값이라 drain 대상이 아니다 (알림 메시지가 읽는다).
 */
@Getter
public final class KisCallStats {

  private final AtomicLong apiCalls = new AtomicLong();
  private final AtomicLong apiFails = new AtomicLong();
  private final AtomicLong rateLimitHits = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();

  /**
   * 영속 대상 카운터(apiCalls, apiFails)를 0 으로 되돌리고 그때까지의 값을 돌려준다. run 카운터 flush 에 쓴다.
   */
  public Snapshot drain() {
    return new Snapshot(apiCalls.getAndSet(0), apiFails.getAndSet(0));
  }

  /**
   * drain 한 값을 되돌린다 (flush 실패 시). 그 사이 다른 스레드가 올린 값을 덮지 않도록 가산한다.
   */
  public void restore(Snapshot snapshot) {
    apiCalls.addAndGet(snapshot.apiCalls());
    apiFails.addAndGet(snapshot.apiFails());
  }

  /** drain 시점의 영속 대상 값 */
  public record Snapshot(long apiCalls, long apiFails) {

    public boolean isEmpty() {
      return apiCalls == 0 && apiFails == 0;
    }
  }
}

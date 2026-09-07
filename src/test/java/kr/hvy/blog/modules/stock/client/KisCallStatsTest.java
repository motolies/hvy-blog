package kr.hvy.blog.modules.stock.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KisCallStatsTest {

  @Test
  @DisplayName("drain 은 영속 대상(apiCalls, apiFails)만 비우고 rateLimitHits/retries 는 누적으로 남긴다")
  void drainResetsOnlyPersistedCounters() {
    KisCallStats stats = new KisCallStats();
    stats.getApiCalls().addAndGet(3);
    stats.getApiFails().addAndGet(1);
    stats.getRateLimitHits().addAndGet(2);
    stats.getRetries().addAndGet(4);

    KisCallStats.Snapshot snapshot = stats.drain();

    assertThat(snapshot.apiCalls()).isEqualTo(3);
    assertThat(snapshot.apiFails()).isEqualTo(1);
    assertThat(snapshot.isEmpty()).isFalse();
    assertThat(stats.getApiCalls().get()).isZero();
    assertThat(stats.getApiFails().get()).isZero();
    assertThat(stats.getRateLimitHits().get()).isEqualTo(2);
    assertThat(stats.getRetries().get()).isEqualTo(4);
    assertThat(stats.drain().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("restore 는 덮어쓰지 않고 가산해 그 사이 올라간 값을 보존한다")
  void restoreAddsBack() {
    KisCallStats stats = new KisCallStats();
    stats.getApiCalls().addAndGet(3);
    KisCallStats.Snapshot snapshot = stats.drain();
    stats.getApiCalls().addAndGet(2); // drain 과 restore 사이에 다른 스레드가 올린 값

    stats.restore(snapshot);

    assertThat(stats.getApiCalls().get()).isEqualTo(5);
    assertThat(stats.getApiFails().get()).isZero();
  }
}

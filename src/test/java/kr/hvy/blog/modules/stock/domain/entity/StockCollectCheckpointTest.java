package kr.hvy.blog.modules.stock.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 체크포인트 상태 전이 중 PAUSED(윈도우 상한): 실패가 아니므로 attempt 를 소모하지 않고 커서를 보존해야 한다.
 */
class StockCollectCheckpointTest {

  private static final LocalDate CURSOR = LocalDate.of(2021, 10, 13);

  @Test
  @DisplayName("start → pause: PAUSED 로 바뀌고 start 가 올린 attempt 가 되돌아가며 커서·확보 범위는 그대로다")
  void pauseRestoresAttemptAndKeepsCursor() {
    StockCollectCheckpoint cp = StockCollectCheckpoint.pending(CollectJobType.INVESTOR_BACKFILL, "005930", LocalDate.of(2026, 9, 8));
    cp.start(7L);
    cp.advance(CURSOR, CURSOR.plusDays(1), LocalDate.of(2026, 9, 7));
    assertThat(cp.getAttemptCount()).isEqualTo(1);

    cp.pause("윈도우 수 상한 도달 (40)");

    assertThat(cp.getStatus()).isEqualTo(CheckpointStatus.PAUSED);
    assertThat(cp.getAttemptCount()).isZero();
    assertThat(cp.getCursorDate()).isEqualTo(CURSOR);
    assertThat(cp.getEarliestLoaded()).isEqualTo(CURSOR.plusDays(1));
    assertThat(cp.getLastRunId()).isEqualTo(7L);
    assertThat(cp.getErrorMessage()).contains("상한");
  }

  @Test
  @DisplayName("start 없이 pause 해도 attempt 는 음수가 되지 않는다")
  void pauseNeverGoesNegative() {
    StockCollectCheckpoint cp = StockCollectCheckpoint.pending(CollectJobType.PRICE_BACKFILL, "000660", LocalDate.of(2026, 9, 8));

    cp.pause("상한");

    assertThat(cp.getAttemptCount()).isZero();
    assertThat(cp.getStatus()).isEqualTo(CheckpointStatus.PAUSED);
  }

  @Test
  @DisplayName("markFailed 는 attempt 를 유지한다 (PAUSED 와 달리 임계를 소모한다)")
  void failedConsumesAttempt() {
    StockCollectCheckpoint cp = StockCollectCheckpoint.pending(CollectJobType.PRICE_BACKFILL, "000660", LocalDate.of(2026, 9, 8));
    cp.start(1L);

    cp.markFailed("boom");

    assertThat(cp.getStatus()).isEqualTo(CheckpointStatus.FAILED);
    assertThat(cp.getAttemptCount()).isEqualTo(1);
  }
}

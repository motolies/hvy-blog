package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 페이저 종료 사유 → 체크포인트 상태 매핑. 윈도우 상한은 실패가 아니라 재개 가능한 PAUSED 다 (지수·일봉·수급·해외 공통).
 */
class StockIndexCollectServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 8);

  @Test
  @DisplayName("REACHED_TARGET → DONE, EXHAUSTED/EMPTY → EXHAUSTED, WINDOW_LIMIT → PAUSED(attempt 미소모)")
  void applyOutcomeMapsTerminationToStatus() {
    assertThat(apply(DateWindowPaginator.Termination.REACHED_TARGET).getStatus()).isEqualTo(CheckpointStatus.DONE);
    assertThat(apply(DateWindowPaginator.Termination.EXHAUSTED).getStatus()).isEqualTo(CheckpointStatus.EXHAUSTED);
    assertThat(apply(DateWindowPaginator.Termination.EMPTY).getStatus()).isEqualTo(CheckpointStatus.EXHAUSTED);

    StockCollectCheckpoint paused = apply(DateWindowPaginator.Termination.WINDOW_LIMIT);
    assertThat(paused.getStatus()).isEqualTo(CheckpointStatus.PAUSED);
    assertThat(paused.getAttemptCount()).isZero();
    assertThat(paused.getErrorMessage()).contains("40");
  }

  private static StockCollectCheckpoint apply(DateWindowPaginator.Termination termination) {
    StockCollectCheckpoint cp = StockCollectCheckpoint.pending(CollectJobType.INVESTOR_BACKFILL, "005930", DAY);
    cp.start(1L);
    StockIndexCollectService.applyOutcome(cp, new DateWindowPaginator.Outcome(termination, DAY.minusYears(5), DAY, 40, 1200));
    return cp;
  }
}

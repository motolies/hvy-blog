package kr.hvy.blog.modules.stock.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;

/**
 * 체크포인트 응답.
 */
public record CollectCheckpointResponse(
    String jobType,
    String targetKey,
    LocalDate cursorDate,
    LocalDate earliestLoaded,
    LocalDate latestLoaded,
    CheckpointStatus status,
    int attemptCount,
    Long lastRunId,
    String errorMessage,
    Instant updatedAt
) {

  /**
   * 엔티티를 응답으로 변환한다.
   */
  public static CollectCheckpointResponse from(StockCollectCheckpoint cp) {
    return new CollectCheckpointResponse(cp.getId().getJobType(), cp.getId().getTargetKey(), cp.getCursorDate(),
        cp.getEarliestLoaded(), cp.getLatestLoaded(), cp.getStatus(), cp.getAttemptCount(), cp.getLastRunId(),
        cp.getErrorMessage(), cp.getUpdatedAt());
  }
}

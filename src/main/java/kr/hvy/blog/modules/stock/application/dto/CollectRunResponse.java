package kr.hvy.blog.modules.stock.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;

/**
 * 수집 run 응답.
 */
public record CollectRunResponse(
    Long runId,
    CollectJobType jobType,
    String jobDescription,
    TriggerType triggerType,
    LocalDate targetDate,
    LocalDate rangeStart,
    LocalDate rangeEnd,
    CollectStatus status,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    long rowsUpserted,
    long apiCallCount,
    long apiFailCount,
    String errorMessage,
    Map<String, Object> metadata
) {

  /**
   * 엔티티를 응답으로 변환한다.
   */
  public static CollectRunResponse from(StockCollectRun run) {
    return new CollectRunResponse(
        run.getRunId(), run.getJobType(), run.getJobType().getDesc(), run.getTriggerType(),
        run.getTargetDate(), run.getRangeStart(), run.getRangeEnd(), run.getStatus(),
        run.getStartedAt(), run.getFinishedAt(), run.getDurationMs(),
        run.getRowsUpserted(), run.getApiCallCount(), run.getApiFailCount(),
        run.getErrorMessage(), run.getMetadataJson());
  }
}

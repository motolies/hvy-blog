package kr.hvy.blog.modules.stock.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.converter.CheckpointStatusConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 종목/지수 단위 재개 지점 (tb_stock_collect_checkpoint). run 을 넘어 살아남는다.
 * <p>
 * cursor_date 는 다음 윈도우의 종료일이며 과거로 밀리며 감소한다. 프로세스가 죽어도 다음 실행이
 * 이 값부터 이어받는다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockCollectCheckpoint {

  private static final int ERROR_MESSAGE_LIMIT = 2000;

  @EmbeddedId
  private CollectCheckpointId id;

  private LocalDate cursorDate;

  private LocalDate earliestLoaded;

  private LocalDate latestLoaded;

  @Convert(converter = CheckpointStatusConverter.class)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private CheckpointStatus status = CheckpointStatus.PENDING;

  @Column(nullable = false)
  @Builder.Default
  private int attemptCount = 0;

  private Long lastRunId;

  @Column(columnDefinition = "TEXT")
  private String errorMessage;

  @Column(nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  /**
   * 대기 상태의 새 체크포인트를 만든다.
   */
  public static StockCollectCheckpoint pending(CollectJobType jobType, String targetKey, LocalDate cursorDate) {
    return StockCollectCheckpoint.builder()
        .id(CollectCheckpointId.of(jobType, targetKey))
        .cursorDate(cursorDate)
        .status(CheckpointStatus.PENDING)
        .build();
  }

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  /**
   * 처리 시작을 기록한다. 시도 횟수를 올리고 IN_PROGRESS 로 바꾼다.
   */
  public void start(Long runId) {
    this.status = CheckpointStatus.IN_PROGRESS;
    this.attemptCount++;
    this.lastRunId = runId;
    this.errorMessage = null;
    touch();
  }

  /**
   * 윈도우 1개를 적재한 뒤 커서와 확보 범위를 갱신한다.
   */
  public void advance(LocalDate nextCursor, LocalDate windowEarliest, LocalDate windowLatest) {
    this.cursorDate = nextCursor;
    if (earliestLoaded == null || (windowEarliest != null && windowEarliest.isBefore(earliestLoaded))) {
      this.earliestLoaded = windowEarliest;
    }
    if (latestLoaded == null || (windowLatest != null && windowLatest.isAfter(latestLoaded))) {
      this.latestLoaded = windowLatest;
    }
    touch();
  }

  /**
   * 목표 시작일까지 확보해 완료 처리한다.
   */
  public void markDone() {
    this.status = CheckpointStatus.DONE;
    touch();
  }

  /**
   * KIS 가 더 과거 데이터를 주지 않아 소급 한계에 도달했음을 기록한다.
   */
  public void markExhausted() {
    this.status = CheckpointStatus.EXHAUSTED;
    touch();
  }

  /**
   * 실패를 기록한다. 재개 대상에 남으며 attempt_count 임계를 넘으면 서비스가 건너뛴다.
   */
  public void markFailed(String message) {
    this.status = CheckpointStatus.FAILED;
    this.errorMessage = StringUtils.abbreviate(message, ERROR_MESSAGE_LIMIT);
    touch();
  }

  /**
   * 윈도우 상한 같은 정상적 중단을 기록한다. 커서를 보존한 채 PAUSED 로 두고 start() 가 올린 시도 횟수를 되돌린다.
   * 실패가 아니므로 attempt 임계를 소모하지 않고, 다음 실행이 커서부터 이어받는다.
   */
  public void pause(String message) {
    this.status = CheckpointStatus.PAUSED;
    this.attemptCount = Math.max(0, this.attemptCount - 1);
    this.errorMessage = StringUtils.abbreviate(message, ERROR_MESSAGE_LIMIT);
    touch();
  }

  /**
   * 커서를 되돌리고 대기 상태로 초기화한다 (부분 재적재).
   */
  public void reset(LocalDate cursorDate) {
    this.cursorDate = cursorDate;
    this.earliestLoaded = null;
    this.latestLoaded = null;
    this.status = CheckpointStatus.PENDING;
    this.attemptCount = 0;
    this.errorMessage = null;
    touch();
  }

  private void touch() {
    this.updatedAt = Instant.now();
  }
}

package kr.hvy.blog.modules.stock.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.code.converter.CollectJobTypeConverter;
import kr.hvy.blog.modules.stock.domain.code.converter.CollectStatusConverter;
import kr.hvy.blog.modules.stock.domain.code.converter.TriggerTypeConverter;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수집 잡 실행 이력 (tb_stock_collect_run).
 * <p>
 * 동일 job_type 의 RUNNING 행은 부분 유니크 인덱스(uk_stock_collect_run_running)가 하나만 허용한다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockCollectRun {

  private static final int ERROR_MESSAGE_LIMIT = 4000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long runId;

  @Convert(converter = CollectJobTypeConverter.class)
  @Column(nullable = false, length = 40)
  private CollectJobType jobType;

  @Convert(converter = TriggerTypeConverter.class)
  @Column(nullable = false, length = 20)
  private TriggerType triggerType;

  private LocalDate targetDate;

  private LocalDate rangeStart;

  private LocalDate rangeEnd;

  @Convert(converter = CollectStatusConverter.class)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private CollectStatus status = CollectStatus.RUNNING;

  @Column(nullable = false)
  @Builder.Default
  private Instant startedAt = Instant.now();

  private Instant finishedAt;

  private Long durationMs;

  @Column(nullable = false)
  @Builder.Default
  private long rowsUpserted = 0L;

  @Column(nullable = false)
  @Builder.Default
  private long apiCallCount = 0L;

  @Column(nullable = false)
  @Builder.Default
  private long apiFailCount = 0L;

  @Column(columnDefinition = "TEXT")
  private String errorMessage;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> metadataJson;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  /**
   * 실행 중인지 판정한다.
   */
  public boolean isRunning() {
    return status == CollectStatus.RUNNING;
  }

  /**
   * 종료 상태로 전환한다. 소요 시간을 계산하고 오류 메시지는 컬럼 상한에 맞춰 자른다.
   */
  public void finish(CollectStatus terminalStatus, String errorMessage) {
    this.status = terminalStatus;
    this.finishedAt = Instant.now();
    this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
    this.errorMessage = StringUtils.abbreviate(errorMessage, ERROR_MESSAGE_LIMIT);
    this.updated.updated();
  }

  /**
   * 부가 정보를 교체한다.
   */
  public void updateMetadata(Map<String, Object> metadata) {
    this.metadataJson = metadata;
    this.updated.updated();
  }
}

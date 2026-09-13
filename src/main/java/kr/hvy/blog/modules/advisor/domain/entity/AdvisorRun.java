package kr.hvy.blog.modules.advisor.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.converter.AdvisorJobTypeConverter;
import kr.hvy.blog.modules.advisor.domain.code.converter.AdvisorStatusConverter;
import kr.hvy.blog.modules.advisor.domain.code.converter.AdvisorTriggerTypeConverter;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 시장 판단 잡 실행 이력 (tb_advisor_run). StockCollectRun 과 같은 구조이되 행 수·API 호출 대신 LLM 토큰·비용을 센다.
 * <p>
 * 동일 job_type 의 RUNNING 행은 부분 유니크 인덱스(uk_advisor_run_running)가 하나만 허용한다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdvisorRun {

  private static final int ERROR_MESSAGE_LIMIT = 4000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long runId;

  @Convert(converter = AdvisorJobTypeConverter.class)
  @Column(nullable = false, length = 30)
  private AdvisorJobType jobType;

  @Convert(converter = AdvisorTriggerTypeConverter.class)
  @Column(nullable = false, length = 20)
  private AdvisorTriggerType triggerType;

  @Convert(converter = AdvisorStatusConverter.class)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private AdvisorStatus status = AdvisorStatus.RUNNING;

  private LocalDate baseDate;

  @Column(length = 80)
  private String model;

  @Column(length = 40)
  private String promptVersion;

  @Column(nullable = false)
  @Builder.Default
  private int llmCalls = 0;

  @Column(nullable = false)
  @Builder.Default
  private int promptTokens = 0;

  @Column(nullable = false)
  @Builder.Default
  private int completionTokens = 0;

  @Column(nullable = false)
  @Builder.Default
  private int reasoningTokens = 0;

  @Column(nullable = false)
  @Builder.Default
  private int cachedTokens = 0;

  @Column(nullable = false, precision = 12, scale = 6)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  @Column(nullable = false)
  @Builder.Default
  private Instant startedAt = Instant.now();

  private Instant finishedAt;

  private Long durationMs;

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
    return status == AdvisorStatus.RUNNING;
  }

  /**
   * 종료 상태로 전환한다. 소요 시간을 계산하고 오류 메시지는 컬럼 상한에 맞춰 자른다.
   */
  public void finish(AdvisorStatus terminalStatus, String errorMessage) {
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

  /**
   * LLM 사용량·모델·프롬프트 버전을 기록한다 (실행 종료 시 1회).
   */
  public void recordUsage(String model, String promptVersion, int llmCalls, int promptTokens, int completionTokens,
      int reasoningTokens, int cachedTokens, BigDecimal costUsd) {
    this.model = model;
    this.promptVersion = promptVersion;
    this.llmCalls = llmCalls;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.reasoningTokens = reasoningTokens;
    this.cachedTokens = cachedTokens;
    this.costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    this.updated.updated();
  }
}

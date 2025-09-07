package kr.hvy.blog.modules.jira.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Jira 워크로그 엔티티
 */
@Entity
@Table(name = "jira_worklog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class JiraWorklog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "issueId", nullable = false)
  @Setter
  private JiraIssue jiraIssue;

  @Column(name = "issueKey", nullable = false, length = 32)
  private String issueKey;

  @Column(name = "issueType", length = 64)
  private String issueType;

  @Column(name = "status", length = 64)
  private String status;

  @Column(name = "issueLink", nullable = false, length = 512)
  private String issueLink;

  @Column(name = "summary", nullable = false, length = 512)
  private String summary;

  @Column(name = "author", nullable = false, length = 128)
  private String author;

  @Column(name = "components", length = 512)
  private String components;

  @Column(name = "timeSpent", nullable = false, length = 32)
  private String timeSpent;

  @Column(name = "timeHours", nullable = false, precision = 5, scale = 2)
  private BigDecimal timeHours;

  @Column(name = "comment", columnDefinition = "LONGTEXT")
  private String comment;

  @Column(name = "started", nullable = false)
  private LocalDateTime started;

  @Column(name = "worklogId", unique = true, nullable = false, length = 256)
  private String worklogId;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "DATETIME(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "DATETIME(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();

  /**
   * 워크로그 정보 업데이트
   */
  public void updateWorklogInfo(String issueType, String status, String summary,
      String components, String timeSpent, BigDecimal timeHours,
      String comment, LocalDateTime started) {
    this.issueType = issueType;
    this.status = status;
    this.summary = summary;
    this.components = components;
    this.timeSpent = timeSpent;
    this.timeHours = timeHours;
    this.comment = comment;
    this.started = started;
  }
}
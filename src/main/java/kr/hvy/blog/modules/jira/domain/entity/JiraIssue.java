package kr.hvy.blog.modules.jira.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.jira.application.dto.JiraWorklogDto;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Jira 이슈 엔티티
 */
@Entity
@Table(name = "jira_issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class JiraIssue {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "jiraIssueId", nullable = false)
  private Long jiraIssueId;

  @Column(name = "issueKey", unique = true, nullable = false, length = 32)
  private String issueKey;

  @Column(name = "issueLink", nullable = false, length = 512)
  private String issueLink;

  @Column(name = "summary", nullable = false, length = 512)
  private String summary;

  @Column(name = "issueType", length = 64)
  private String issueType;

  @Column(name = "status", length = 64)
  private String status;

  @Column(name = "assignee", length = 128)
  private String assignee;

  @Column(name = "components", length = 512)
  private String components;

  @Column(name = "storyPoints", precision = 5, scale = 2)
  private BigDecimal storyPoints;

  @Column(name = "startDate")
  private LocalDate startDate;

  @Column(name = "sprint", length = 32)
  private String sprint;

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

  @OneToMany(mappedBy = "jiraIssue", cascade = CascadeType.ALL, orphanRemoval = true)
  @Builder.Default
  private List<JiraWorklog> worklogs = new ArrayList<>();

  /**
   * 이슈 정보 업데이트
   */
  public void updateIssueInfo(String summary, String issueType, String status,
      String assignee, String components, BigDecimal storyPoints,
      LocalDate startDate, String sprint) {
    this.summary = summary;
    this.issueType = issueType;
    this.status = status;
    this.assignee = assignee;
    this.components = components;
    this.storyPoints = storyPoints;
    this.startDate = startDate;
    this.sprint = sprint;
  }

  /**
   * 워크로그 추가 (DDD)
   */
  public void addWorklog(JiraWorklogDto worklogDto) {
    JiraWorklog worklog = JiraWorklog.builder()
        .jiraIssue(this)
        .issueKey(worklogDto.getIssueKey())
        .issueType(worklogDto.getIssueType())
        .status(worklogDto.getStatus())
        .issueLink(worklogDto.getIssueLink())
        .summary(worklogDto.getSummary())
        .author(worklogDto.getAuthor())
        .components(worklogDto.getComponents())
        .timeSpent(worklogDto.getTimeSpent())
        .timeHours(worklogDto.getTimeHours())
        .comment(worklogDto.getComment())
        .started(worklogDto.getStarted())
        .worklogId(worklogDto.getWorklogId())
        .build();

    this.worklogs.add(worklog);
    worklog.setJiraIssue(this);
  }

  /**
   * 기존 워크로그 엔티티 추가 (내부용)
   */
  public void addWorklogEntity(JiraWorklog worklog) {
    this.worklogs.add(worklog);
    worklog.setJiraIssue(this);
  }

  /**
   * 워크로그 업데이트 (DDD)
   */
  public boolean updateWorklog(JiraWorklogDto worklogDto) {
    Optional<JiraWorklog> existingWorklog = findWorklogByWorklogId(worklogDto.getWorklogId());

    if (existingWorklog.isPresent()) {
      existingWorklog.get().updateWorklogInfo(
          worklogDto.getIssueType(),
          worklogDto.getStatus(),
          worklogDto.getSummary(),
          worklogDto.getComponents(),
          worklogDto.getTimeSpent(),
          worklogDto.getTimeHours(),
          worklogDto.getComment(),
          worklogDto.getStarted()
      );
      return true;
    }
    return false;
  }

  /**
   * 워크로그 추가 또는 업데이트 (DDD)
   */
  public void addOrUpdateWorklog(JiraWorklogDto worklogDto) {
    boolean updated = updateWorklog(worklogDto);
    if (!updated) {
      addWorklog(worklogDto);
    }
  }

  /**
   * 워크로그ID로 워크로그 찾기 (DDD)
   */
  public Optional<JiraWorklog> findWorklogByWorklogId(String worklogId) {
    return this.worklogs.stream()
        .filter(worklog -> worklogId.equals(worklog.getWorklogId()))
        .findFirst();
  }

  /**
   * 워크로그 제거 (DDD)
   */
  public boolean removeWorklogByWorklogId(String worklogId) {
    Optional<JiraWorklog> worklog = findWorklogByWorklogId(worklogId);
    if (worklog.isPresent()) {
      removeWorklogEntity(worklog.get());
      return true;
    }
    return false;
  }

  /**
   * 워크로그 엔티티 제거 (내부용)
   */
  public void removeWorklogEntity(JiraWorklog worklog) {
    this.worklogs.remove(worklog);
    worklog.setJiraIssue(null);
  }

  /**
   * 모든 워크로그 동기화 (DDD)
   * 기존 워크로그는 업데이트, 새로운 워크로그는 추가
   */
  public void syncWorklogs(List<JiraWorklogDto> worklogDtos) {
    if (worklogDtos == null || worklogDtos.isEmpty()) {
      return;
    }

    for (JiraWorklogDto worklogDto : worklogDtos) {
      addOrUpdateWorklog(worklogDto);
    }
  }

  /**
   * 워크로그 개수 조회 (DDD)
   */
  public int getWorklogCount() {
    return this.worklogs.size();
  }

  /**
   * 특정 작성자의 워크로그 조회 (DDD)
   */
  public List<JiraWorklog> getWorklogsByAuthor(String author) {
    return this.worklogs.stream()
        .filter(worklog -> author.equals(worklog.getAuthor()))
        .toList();
  }

  /**
   * 모든 워크로그 제거 (DDD)
   */
  public void clearWorklogs() {
    this.worklogs.clear();
  }
}
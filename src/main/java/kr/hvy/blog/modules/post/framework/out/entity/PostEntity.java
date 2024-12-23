package kr.hvy.blog.modules.post.framework.out.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Entity
@Table(name = "post", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"id", "status"})
})
@Data
@Builder
@With
@AllArgsConstructor
@NoArgsConstructor
public class PostEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PostStatus status;

  @Column(nullable = false, length = 512)
  private String subject;

  @Column(nullable = false, columnDefinition = "LONGTEXT")
  private String body;

  @Column(nullable = false)
  private String categoryId;

  @Column(nullable = false)
  private boolean publicAccess;

  @Column(nullable = false)
  private boolean mainPage;

  @Column(nullable = false)
  private int viewCount;

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
}
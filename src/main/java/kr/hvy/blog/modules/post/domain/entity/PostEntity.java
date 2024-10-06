package kr.hvy.blog.modules.post.domain.entity;

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
import kr.hvy.common.domain.embeddable.CreateUpdateDateEntity;
import kr.hvy.common.domain.vo.CreateUpdateDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Entity
@Table(name = "posts", uniqueConstraints = {
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
  private boolean isPublic;

  @Column(nullable = false)
  private boolean isMain;

  @Column(nullable = false)
  private int viewCount;

  @Embedded
  @Builder.Default
  private CreateUpdateDateEntity createUpdateDate = CreateUpdateDateEntity.defaultValues();
}
package kr.hvy.blog.modules.post.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.hvy.common.core.security.SecurityUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PostDraft {

  @Id
  @Column(name = "post_id")
  private Long postId;

  @Column(nullable = false, length = 512)
  private String subject;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;

  public void update(String subject, String body) {
    this.subject = subject;
    this.body = body;
    this.updatedAt = Instant.now();
    this.updatedBy = SecurityUtils.getUsername();
  }
}

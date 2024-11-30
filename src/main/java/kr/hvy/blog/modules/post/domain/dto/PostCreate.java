package kr.hvy.blog.modules.post.domain.dto;

import jakarta.persistence.Column;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostCreate {
  @Builder.Default
  String subject = "";
  @Builder.Default
  String body = "";
  @Builder.Default
  String categoryId = "";
  @Builder.Default
  boolean isPublic = false;
  @Builder.Default
  boolean isMain = false;
}

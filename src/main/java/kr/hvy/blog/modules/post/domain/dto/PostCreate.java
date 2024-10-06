package kr.hvy.blog.modules.post.domain.dto;

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
  PostStatus status = PostStatus.TEMP;
}

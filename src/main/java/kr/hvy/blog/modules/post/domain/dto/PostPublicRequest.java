package kr.hvy.blog.modules.post.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostPublicRequest {
  private Long id;
  private boolean isPublicStatus;
}

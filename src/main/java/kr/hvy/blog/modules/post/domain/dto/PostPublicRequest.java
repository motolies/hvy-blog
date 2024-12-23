package kr.hvy.blog.modules.post.domain.dto;

import lombok.Data;

@Data
public class PostPublicRequest {
  private Long id;
  private boolean isPublicStatus;
}

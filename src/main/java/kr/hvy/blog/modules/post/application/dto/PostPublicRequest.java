package kr.hvy.blog.modules.post.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostPublicRequest {

  Long id;
  boolean isPublicStatus;
}

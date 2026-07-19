package kr.hvy.blog.modules.post.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostRelatedResponse {

  long id;
  String subject;
  String categoryName;
  Instant createDate;
  int commonTagCount;
}

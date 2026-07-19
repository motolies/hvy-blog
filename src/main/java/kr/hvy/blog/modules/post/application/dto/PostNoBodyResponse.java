package kr.hvy.blog.modules.post.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostNoBodyResponse {

  int id;
  String subject;
  String categoryName;
  int viewCount;
  Instant createDate;
  Instant updateDate;

}


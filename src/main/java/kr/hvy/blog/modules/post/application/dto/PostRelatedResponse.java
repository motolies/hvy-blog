package kr.hvy.blog.modules.post.application.dto;

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
  java.sql.Timestamp createDate;
  int commonTagCount;
}

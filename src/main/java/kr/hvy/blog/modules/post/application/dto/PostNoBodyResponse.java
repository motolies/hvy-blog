package kr.hvy.blog.modules.post.application.dto;

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
  java.sql.Timestamp createDate;
  java.sql.Timestamp updateDate;

}


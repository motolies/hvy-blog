package kr.hvy.blog.modules.post.domain.dto;

import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PostResponse {

  Long id;
  PostStatus status;
  String subject;
  String body;
  String categoryId;
  boolean isPublic;
  boolean isMain;
  int viewCount;
  EventLog created;
  EventLog updated;
}

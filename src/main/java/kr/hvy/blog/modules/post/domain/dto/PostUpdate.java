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
public class PostUpdate {
  PostStatus status;
  String subject;
  String body;
  String categoryId;
  boolean isPublic;
  boolean isMain;
}

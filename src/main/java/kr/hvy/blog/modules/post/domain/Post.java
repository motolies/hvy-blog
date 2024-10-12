package kr.hvy.blog.modules.post.domain;

import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.common.domain.vo.CreateUpdateDate;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class Post {

  /**
   * id, status를 유니크로 사용할 예정
   */

  Long id;
  PostStatus status;
  String subject;
  String body;
  String categoryId;
  boolean isPublic;
  boolean isMain;
  int viewCount;
  @Builder.Default
  CreateUpdateDate createUpdateDate = CreateUpdateDate.defaultValues();

}

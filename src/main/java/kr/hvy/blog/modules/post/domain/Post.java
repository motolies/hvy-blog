package kr.hvy.blog.modules.post.domain;

import java.util.HashSet;
import java.util.Set;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class Post {

  /**
   * todo : id, status를 유니크로 사용할 예정
   */
  Long id;
  @Builder.Default
  PostStatus status = PostStatus.TEMP;
  String subject;
  String body;
  String categoryId;
  boolean isPublic;
  boolean isMain;
  int viewCount;

  @Builder.Default
  Set<Tag> tags = new HashSet<>();

  @Builder.Default
  Set<File> files = new HashSet<>();

  @Builder.Default
  EventLog created = EventLog.defaultValues();

  @Builder.Default
  EventLog updated = EventLog.defaultValues();

}

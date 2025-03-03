package kr.hvy.blog.modules.post.domain;

import java.util.HashSet;
import java.util.Set;
import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class Post {

  Long id;
  String subject;
  String body;
  boolean isPublic;
  boolean isMain;
  int viewCount;
  String categoryId;
  Category category;

  @Builder.Default
  Set<Tag> tags = new HashSet<>();

  @Builder.Default
  Set<File> files = new HashSet<>();

  @Builder.Default
  EventLog created = EventLog.defaultValues();

  @Builder.Default
  EventLog updated = EventLog.defaultValues();

}

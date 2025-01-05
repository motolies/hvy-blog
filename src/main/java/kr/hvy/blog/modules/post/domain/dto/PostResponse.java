package kr.hvy.blog.modules.post.domain.dto;

import java.util.Set;
import kr.hvy.blog.modules.file.domain.dto.FileResponse;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;
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
  Set<TagResponse> tags;
  Set<FileResponse> files;
  EventLog created;
  EventLog updated;
}

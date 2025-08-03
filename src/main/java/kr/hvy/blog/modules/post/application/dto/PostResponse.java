package kr.hvy.blog.modules.post.application.dto;

import java.util.Set;
import kr.hvy.blog.modules.category.application.dto.CategorySingleResponse;
import kr.hvy.blog.modules.file.application.dto.FileResponse;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostResponse {

  Long id;
  String subject;
  String body;
  String categoryId;
  CategorySingleResponse category;
  boolean isPublic;
  boolean isMain;
  int viewCount;
  Set<TagResponse> tags;
  Set<FileResponse> files;
  EventLog created;
  EventLog updated;
}

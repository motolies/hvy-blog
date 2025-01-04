package kr.hvy.blog.modules.tag.domain.dto;

import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TagResponse {

  Long id;
  String name;
  int postCount;
}

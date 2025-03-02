package kr.hvy.blog.modules.tag.domain.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TagResponse {

  Long id;
  String name;
  int postCount;

  public String getLabel() {
    return name;
  }
}

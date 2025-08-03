package kr.hvy.blog.modules.tag.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class TagResponse {

  Long id;
  String name;
  int postCount;

  public String getLabel() {
    return name;
  }
}

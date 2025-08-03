package kr.hvy.blog.modules.post.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostSearchEngineResponse {

  Long id;
  String name;
  String url;
  int order;

}

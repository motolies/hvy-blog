package kr.hvy.blog.modules.post.domain.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PostSearchEngineResponse {
  Long id;
  String name;
  String url;
  int order;

}

package kr.hvy.blog.modules.post.application.dto;

import java.io.Serializable;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostPrevNextResponse implements Serializable {

  int prev;
  int next;

}

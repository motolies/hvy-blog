package kr.hvy.blog.modules.memo.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class MemoCategoryResponse {

  Long id;
  String name;
  int seq;
}

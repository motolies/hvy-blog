package kr.hvy.blog.modules.memo.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class MemoCategoryUpdate {

  @NotBlank(message = "카테고리 이름은 필수입니다.")
  String name;

  int seq;
}

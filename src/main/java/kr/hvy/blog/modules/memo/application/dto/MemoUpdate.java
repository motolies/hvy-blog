package kr.hvy.blog.modules.memo.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class MemoUpdate {

  @NotBlank(message = "메모 내용은 필수입니다.")
  String content;

  Long categoryId;
}

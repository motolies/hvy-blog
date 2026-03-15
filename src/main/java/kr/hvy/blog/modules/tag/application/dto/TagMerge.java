package kr.hvy.blog.modules.tag.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class TagMerge {

  @NotNull(message = "원본 태그 ID는 필수 입니다. ")
  Long sourceTagId;

  @NotNull(message = "대상 태그 ID는 필수 입니다. ")
  Long targetTagId;
}

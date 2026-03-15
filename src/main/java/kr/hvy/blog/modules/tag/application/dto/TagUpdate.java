package kr.hvy.blog.modules.tag.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class TagUpdate {

  @NotBlank(message = "태그 이름은 필수 입니다. ")
  @Size(max = 64, message = "태그 이름은 64자 이내여야 합니다. ")
  String name;
}

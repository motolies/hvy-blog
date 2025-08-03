package kr.hvy.blog.modules.tag.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class TagCreate {
  @NotNull(message = "태그 이름은 필수 입니다. ")
  String name;
}

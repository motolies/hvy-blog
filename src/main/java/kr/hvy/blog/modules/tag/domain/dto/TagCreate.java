package kr.hvy.blog.modules.tag.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagCreate {
  @NotNull(message = "태그 이름은 필수 입니다. ")
  String name;
}

package kr.hvy.blog.modules.post.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostUpdate {

  @NotNull(message = "제목은 필수 입니다. ")
  String subject;
  @NotNull(message = "내용은 필수 입니다. ")
  String body;
  @NotNull(message = "카테고리는 필수 입니다. ")
  String categoryId;
  boolean isPublic;
  boolean isMain;
}

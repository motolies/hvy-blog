package kr.hvy.blog.modules.post.application.dto;

import kr.hvy.blog.modules.category.domain.code.CategoryConstant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class PostCreate {

  @Builder.Default
  String subject = "";
  @Builder.Default
  String body = "";
  @Builder.Default
  String categoryId = CategoryConstant.ROOT_CATEGORY_ID;
  @Builder.Default
  boolean isPublic = false;
  @Builder.Default
  boolean isMain = false;
}

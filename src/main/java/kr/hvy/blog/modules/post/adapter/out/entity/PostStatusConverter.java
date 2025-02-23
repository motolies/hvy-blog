package kr.hvy.blog.modules.post.adapter.out.entity;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.common.code.base.AbstractEnumCodeConverter;

@Converter(autoApply = true)
public class PostStatusConverter extends AbstractEnumCodeConverter<PostStatus, String> {

  protected PostStatusConverter() {
    super(PostStatus.class);
  }
}
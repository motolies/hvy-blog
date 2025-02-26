package kr.hvy.blog.modules.auth.adapter.out.entity;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.common.code.base.AbstractEnumCodeConverter;

@Converter(autoApply = true)
public class AuthorityNameConverter extends AbstractEnumCodeConverter<AuthorityName, String> {

  protected AuthorityNameConverter() {
    super(AuthorityName.class);
  }
}

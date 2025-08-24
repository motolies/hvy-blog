package kr.hvy.blog.modules.auth.domain.code.converter;

import jakarta.persistence.Converter;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;

@Converter(autoApply = true)
public class AuthorityNameConverter extends AbstractEnumCodeConverter<AuthorityName, String> {

  protected AuthorityNameConverter() {
    super(AuthorityName.class);
  }
}

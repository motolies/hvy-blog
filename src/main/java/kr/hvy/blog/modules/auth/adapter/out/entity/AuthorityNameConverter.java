package kr.hvy.blog.modules.auth.adapter.out.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;

@Converter(autoApply = true)
public class AuthorityNameConverter implements AttributeConverter<AuthorityName, String> {

  @Override
  public String convertToDatabaseColumn(AuthorityName authorityName) {
    if (authorityName == null) {
      return null;
    }
    return authorityName.getCode();
  }

  @Override
  public AuthorityName convertToEntityAttribute(String code) {
    if (code == null) {
      return null;
    }
    return AuthorityName.valueOf(code);
  }
}

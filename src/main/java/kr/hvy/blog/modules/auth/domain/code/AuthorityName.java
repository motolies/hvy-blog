package kr.hvy.blog.modules.auth.domain.code;


import kr.hvy.common.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AuthorityName implements EnumCode<String> {

  ROLE_USER("ROLE_USER", "사용자"),
  ROLE_ADMIN("ROLE_ADMIN", "관리자");

  private final String code;
  private final String desc;
}

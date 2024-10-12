package kr.hvy.blog.modules.auth.domain;

import java.util.Set;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class User {

  Long id;
  String name;
  String username;
  String password;
  Boolean isEnabled;
  Set<AuthorityName> authorities;

}

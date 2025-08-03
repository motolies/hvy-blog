package kr.hvy.blog.modules.auth.application.dto;

import java.util.Set;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.Builder;
import lombok.Data;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class UserResponse {

  Long id;
  String name;
  String username;
  Boolean isEnabled;
  Set<AuthorityName> authorities;
}

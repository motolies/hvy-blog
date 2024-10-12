package kr.hvy.blog.modules.auth.domain.dto;

import java.util.Set;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.Builder;
import lombok.Data;
import lombok.Value;
import lombok.With;

@Data
@Builder
public class UserResponse {

  Long id;
  String name;
  String username;
  Boolean isEnabled;
  Set<AuthorityName> authorities;
}

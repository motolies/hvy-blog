package kr.hvy.blog.modules.auth.domain.dto;

import java.util.Set;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreate {
  String name;
  String username;
  String password;
  Set<AuthorityName> authorities;
}

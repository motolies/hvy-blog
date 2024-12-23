package kr.hvy.blog.modules.auth.domain;

import java.util.Set;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Value
@Builder
@With
public class SecurityUser implements UserDetails {

  Long id;
  String name;
  String username;
  String password;
  Boolean isEnabled;
  Set<GrantedAuthority> authorities;

}

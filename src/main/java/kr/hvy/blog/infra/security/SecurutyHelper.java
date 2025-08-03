package kr.hvy.blog.infra.security;


import java.util.Optional;
import kr.hvy.blog.modules.auth.domain.SecurityUser;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurutyHelper {


  /**
   * Gets current login user.
   *
   * @return the current user
   */
  public static SecurityUser getCurrentUser() {
    return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .map(SecurityUser.class::cast)
        .orElse(null);
  }


  /**
   * Is admin boolean.
   *
   * @return the boolean
   */
  public static boolean isAdmin() {
    SecurityUser user = getCurrentUser();
    if (user == null) {
      return false;
    } else {
      return user.getAuthorities().stream()
          .anyMatch(auth -> auth.getAuthority().equals(AuthorityName.ROLE_ADMIN.getCode()));
    }
  }
}

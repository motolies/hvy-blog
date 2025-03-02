package kr.hvy.blog.modules.auth.domain.dto;

import jakarta.validation.constraints.NotNull;
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
  @NotNull(message = "이름은 필수 입니다. ")
  String name;
  @NotNull(message = "아이디는 필수 입니다. ")
  String username;
  @NotNull(message = "비밀번호는 필수 입니다. ")
  String password;
  Set<AuthorityName> authorities;
}

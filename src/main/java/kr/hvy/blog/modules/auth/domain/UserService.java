package kr.hvy.blog.modules.auth.domain;

import kr.hvy.blog.modules.auth.domain.dto.LoginRequest;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.specification.UserCreateSpecification;
import kr.hvy.blog.modules.auth.domain.specification.UserLoginSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

  private final PasswordEncoder passwordEncoder;

  public void login(User user, LoginRequest loginRequest) {
    if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
      throw new IllegalArgumentException("Password does not match.");
    }

    UserLoginSpecification userLoginSpecification = new UserLoginSpecification();
    if (userLoginSpecification.not().isSatisfiedBy(user)) {
      throw new IllegalStateException("사용할 수 없는 사용자 입니다.");
    }
  }

  public User createUser(User tempUser, UserCreate userCreate) {
    User userSetPass = tempUser
        .withPassword(passwordEncoder.encode(userCreate.getPassword()));

    UserCreateSpecification userCreateSpecification = new UserCreateSpecification();
    if (userCreateSpecification.not().isSatisfiedBy(userSetPass)) {
      throw new IllegalArgumentException("잘못된 파라미터를 사용하여 사용자를 생성할 수 없습니다.");
    }
    return userSetPass;
  }

}

package kr.hvy.blog.modules.auth.domain;

import java.util.Base64;
import kr.hvy.blog.modules.auth.adapter.out.redis.RedisRsa;
import kr.hvy.blog.modules.auth.domain.dto.LoginRequest;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.specification.UserCreateSpecification;
import kr.hvy.blog.modules.auth.domain.specification.UserLoginSpecification;
import kr.hvy.common.security.encrypt.RSAEncrypt;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

  private final PasswordEncoder passwordEncoder;

  public void login(User user, LoginRequest loginRequest, RedisRsa rsa) {

    String password = null;
    try {
      password = RSAEncrypt.getDecryptMessage(loginRequest.getPassword(), Base64.getDecoder().decode(rsa.getPrivateKey()));
    } catch (Exception e) {
      throw new IllegalArgumentException("Password does not match.");
    }

    if (!passwordEncoder.matches(password, user.getPassword())) {
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

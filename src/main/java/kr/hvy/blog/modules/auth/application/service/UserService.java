package kr.hvy.blog.modules.auth.application.service;

import java.util.Base64;
import kr.hvy.blog.infra.security.JwtTokenProvider;
import kr.hvy.blog.modules.auth.application.dto.LoginRequest;
import kr.hvy.blog.modules.auth.application.dto.RsaKeyResponse;
import kr.hvy.blog.modules.auth.application.dto.UserCreate;
import kr.hvy.blog.modules.auth.application.dto.UserResponse;
import kr.hvy.blog.modules.auth.application.specification.UserCreateSpecification;
import kr.hvy.blog.modules.auth.application.specification.UserLoginSpecification;
import kr.hvy.blog.modules.auth.domain.RedisRsa;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.entity.Authority;
import kr.hvy.blog.modules.auth.domain.entity.User;
import kr.hvy.blog.modules.auth.mapper.UserDtoMapper;
import kr.hvy.blog.modules.auth.repository.AuthorityRepository;
import kr.hvy.blog.modules.auth.repository.UserRepository;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.security.SecurityUtils;
import kr.hvy.common.security.encrypt.RSAEncrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserService implements UserDetailsService {


  public static final UserCreateSpecification USER_CREATE_SPECIFICATION = new UserCreateSpecification();
  public static final UserLoginSpecification USER_LOGIN_SPECIFICATION = new UserLoginSpecification();
  private final JwtTokenProvider jwtTokenProvider;
  private final UserDtoMapper userDtoMapper;
  private final PasswordEncoder passwordEncoder;
  private final RsaService rsaService;
  private final UserRepository userRepository;
  private final AuthorityRepository authorityRepository;

  public RsaKeyResponse getRsaKey() {
    String rsaKey = rsaService.getRandomRsaPublicKey();
    return RsaKeyResponse.builder().publicKey(rsaKey).build();
  }

  public UserResponse getProfile() {
    String loginId = SecurityUtils.getUsername();
    User user = findByUsername(loginId);
    return userDtoMapper.toResponse(user);
  }

  public UserResponse login(LoginRequest loginRequest) {
    User user = findByUsername(loginRequest.getUsername());
    RedisRsa rsa = rsaService.findByPublicKey(loginRequest.getPublicKey());
    login(user, loginRequest, rsa);
    return userDtoMapper.toResponse(user);
  }

  private void login(User user, LoginRequest loginRequest, RedisRsa rsa) {

    String password = null;
    try {
      password = RSAEncrypt.getDecryptMessage(loginRequest.getPassword(), Base64.getDecoder().decode(rsa.getPrivateKey()));
    } catch (Exception e) {
      throw new BadCredentialsException("Password rsa decrypt error.");
    }

    if (!passwordEncoder.matches(password, user.getPassword())) {
      throw new BadCredentialsException("Password does not match.");
    }

    if (USER_LOGIN_SPECIFICATION.not().isSatisfiedBy(user)) {
      throw new BadCredentialsException("사용할 수 없는 사용자 입니다.");
    }
  }

  public String makeToken(UserResponse userResponse) {
    User user = userDtoMapper.toDomain(userResponse);
    return jwtTokenProvider.createToken(user);
  }

  public UserResponse create(UserCreate userCreate) {
    User user = userDtoMapper.toDomain(userCreate);
    User createUser = createUser(user, userCreate);
    User savedUser = create(createUser);
    return userDtoMapper.toResponse(savedUser);
  }

  private User createUser(User tempUser, UserCreate userCreate) {
    String encodedPassword = passwordEncoder.encode(userCreate.getPassword());
    User userSetPass = userDtoMapper.createUserWithEncodedPassword(tempUser, encodedPassword);

    if (USER_CREATE_SPECIFICATION.not().isSatisfiedBy(userSetPass)) {
      throw new IllegalArgumentException("잘못된 파라미터를 사용하여 사용자를 생성할 수 없습니다.");
    }
    return userSetPass;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    User user = findByUsername(username);
    return userDtoMapper.toSecurityUser(user);
  }


  private User findByUsername(String username) {
    return userRepository.findByUsername(username)
        .orElseThrow(() -> new DataNotFoundException("User not found."));
  }

  private User create(User user) {
    user.getAuthorities().forEach(tempAuth -> {
      Authority authority = loadAuthority(tempAuth.getName());
      user.removeAuthority(tempAuth);
      user.addAuthority(authority);
    });
    return userRepository.save(user);
  }

  private Authority loadAuthority(AuthorityName authority) {
    return authorityRepository.findByName(authority)
        .orElse(Authority.builder().name(authority).build());
  }

}

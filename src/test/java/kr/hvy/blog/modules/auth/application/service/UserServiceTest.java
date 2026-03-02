package kr.hvy.blog.modules.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.auth.application.dto.LoginRequest;
import kr.hvy.blog.modules.auth.application.dto.RsaKeyResponse;
import kr.hvy.blog.modules.auth.application.dto.UserCreate;
import kr.hvy.blog.modules.auth.application.dto.UserResponse;
import kr.hvy.blog.modules.auth.domain.RedisRsa;
import kr.hvy.blog.modules.auth.domain.SecurityUser;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.entity.Authority;
import kr.hvy.blog.modules.auth.domain.entity.User;
import kr.hvy.blog.modules.auth.mapper.UserDtoMapper;
import kr.hvy.blog.modules.auth.repository.AuthorityRepository;
import kr.hvy.blog.modules.auth.repository.UserRepository;
import kr.hvy.blog.infra.security.JwtTokenProvider;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.security.SecurityUtils;
import kr.hvy.common.core.security.encrypt.RSAEncrypt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock
  JwtTokenProvider jwtTokenProvider;

  @Mock
  UserDtoMapper userDtoMapper;

  @Mock
  PasswordEncoder passwordEncoder;

  @Mock
  RsaService rsaService;

  @Mock
  UserRepository userRepository;

  @Mock
  AuthorityRepository authorityRepository;

  @InjectMocks
  UserService userService;

  private User createUser(Long id, String name, String username, String password, Boolean isEnabled) {
    return User.builder()
        .id(id)
        .name(name)
        .username(username)
        .password(password)
        .isEnabled(isEnabled)
        .authorities(new HashSet<>())
        .build();
  }

  @Nested
  @DisplayName("getRsaKey")
  class GetRsaKey {

    @Test
    @DisplayName("RSA 공개키를 반환한다")
    void getRsaKey_returnsRsaKeyResponse() {
      // Given
      String publicKey = "testPublicKey";
      given(rsaService.getRandomRsaPublicKey()).willReturn(publicKey);

      // When
      RsaKeyResponse result = userService.getRsaKey();

      // Then
      assertThat(result.getPublicKey()).isEqualTo(publicKey);
    }
  }

  @Nested
  @DisplayName("getProfile")
  class GetProfile {

    @Test
    @DisplayName("인증된 사용자의 프로필을 반환한다")
    void getProfile_authenticatedUser_returnsUserResponse() {
      try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
        // Given
        securityMock.when(SecurityUtils::getUsername).thenReturn("admin");
        User user = createUser(1L, "관리자", "admin", "encodedPw", true);
        UserResponse expected = UserResponse.builder()
            .id(1L).name("관리자").username("admin").isEnabled(true).build();
        given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
        given(userDtoMapper.toResponse(user)).willReturn(expected);

        // When
        UserResponse result = userService.getProfile();

        // Then
        assertThat(result).isEqualTo(expected);
      }
    }

    @Test
    @DisplayName("사용자가 없으면 DataNotFoundException이 발생한다")
    void getProfile_userNotFound_throwsDataNotFoundException() {
      try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
        // Given
        securityMock.when(SecurityUtils::getUsername).thenReturn("unknown");
        given(userRepository.findByUsername("unknown")).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> userService.getProfile())
            .isInstanceOf(DataNotFoundException.class);
      }
    }
  }

  @Nested
  @DisplayName("login")
  class Login {

    @Test
    @DisplayName("정상 로그인이면 UserResponse를 반환한다")
    void login_validCredentials_returnsUserResponse() {
      try (MockedStatic<RSAEncrypt> rsaMock = mockStatic(RSAEncrypt.class)) {
        // Given
        String publicKey = "testPublicKey";
        String privateKey = java.util.Base64.getEncoder().encodeToString("testPrivateKey".getBytes());
        String encryptedPassword = "encryptedPw";
        String decryptedPassword = "plainPw";
        String encodedPassword = "encodedPw";

        User user = createUser(1L, "관리자", "admin", encodedPassword, true);
        RedisRsa rsa = RedisRsa.builder().publicKey(publicKey).privateKey(privateKey).build();
        LoginRequest loginRequest = LoginRequest.builder()
            .username("admin").password(encryptedPassword).publicKey(publicKey).build();
        UserResponse expected = UserResponse.builder()
            .id(1L).name("관리자").username("admin").isEnabled(true).build();

        given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
        given(rsaService.findByPublicKey(publicKey)).willReturn(rsa);
        rsaMock.when(() -> RSAEncrypt.getDecryptMessage(any(String.class), any(byte[].class))).thenReturn(decryptedPassword);
        given(passwordEncoder.matches(decryptedPassword, encodedPassword)).willReturn(true);
        given(userDtoMapper.toResponse(user)).willReturn(expected);

        // When
        UserResponse result = userService.login(loginRequest);

        // Then
        assertThat(result).isEqualTo(expected);
      }
    }

    @Test
    @DisplayName("사용자가 없으면 DataNotFoundException이 발생한다")
    void login_userNotFound_throwsDataNotFoundException() {
      // Given
      LoginRequest loginRequest = LoginRequest.builder()
          .username("unknown").password("pw").publicKey("key").build();
      given(userRepository.findByUsername("unknown")).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> userService.login(loginRequest))
          .isInstanceOf(DataNotFoundException.class);
    }

    @Test
    @DisplayName("RSA 키가 없으면 DataNotFoundException이 발생한다")
    void login_rsaKeyNotFound_throwsDataNotFoundException() {
      // Given
      String publicKey = "invalidKey";
      User user = createUser(1L, "관리자", "admin", "encodedPw", true);
      LoginRequest loginRequest = LoginRequest.builder()
          .username("admin").password("pw").publicKey(publicKey).build();
      given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
      given(rsaService.findByPublicKey(publicKey)).willThrow(new DataNotFoundException("RSA key not found"));

      // When & Then
      assertThatThrownBy(() -> userService.login(loginRequest))
          .isInstanceOf(DataNotFoundException.class);
    }

    @Test
    @DisplayName("RSA 복호화에 실패하면 BadCredentialsException이 발생한다")
    void login_rsaDecryptFailed_throwsBadCredentialsException() {
      try (MockedStatic<RSAEncrypt> rsaMock = mockStatic(RSAEncrypt.class)) {
        // Given
        String publicKey = "testPublicKey";
        String privateKey = java.util.Base64.getEncoder().encodeToString("testPrivateKey".getBytes());
        User user = createUser(1L, "관리자", "admin", "encodedPw", true);
        RedisRsa rsa = RedisRsa.builder().publicKey(publicKey).privateKey(privateKey).build();
        LoginRequest loginRequest = LoginRequest.builder()
            .username("admin").password("badEncrypted").publicKey(publicKey).build();

        given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
        given(rsaService.findByPublicKey(publicKey)).willReturn(rsa);
        rsaMock.when(() -> RSAEncrypt.getDecryptMessage(any(String.class), any(byte[].class)))
            .thenThrow(new RuntimeException("decrypt error"));

        // When & Then
        assertThatThrownBy(() -> userService.login(loginRequest))
            .isInstanceOf(BadCredentialsException.class);
      }
    }

    @Test
    @DisplayName("비밀번호가 불일치하면 BadCredentialsException이 발생한다")
    void login_passwordMismatch_throwsBadCredentialsException() {
      try (MockedStatic<RSAEncrypt> rsaMock = mockStatic(RSAEncrypt.class)) {
        // Given
        String publicKey = "testPublicKey";
        String privateKey = java.util.Base64.getEncoder().encodeToString("testPrivateKey".getBytes());
        User user = createUser(1L, "관리자", "admin", "encodedPw", true);
        RedisRsa rsa = RedisRsa.builder().publicKey(publicKey).privateKey(privateKey).build();
        LoginRequest loginRequest = LoginRequest.builder()
            .username("admin").password("wrongPw").publicKey(publicKey).build();

        given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
        given(rsaService.findByPublicKey(publicKey)).willReturn(rsa);
        rsaMock.when(() -> RSAEncrypt.getDecryptMessage(any(String.class), any(byte[].class))).thenReturn("decryptedPw");
        given(passwordEncoder.matches("decryptedPw", "encodedPw")).willReturn(false);

        // When & Then
        assertThatThrownBy(() -> userService.login(loginRequest))
            .isInstanceOf(BadCredentialsException.class);
      }
    }

    @Test
    @DisplayName("비활성 사용자로 로그인하면 BadCredentialsException이 발생한다")
    void login_disabledUser_throwsBadCredentialsException() {
      try (MockedStatic<RSAEncrypt> rsaMock = mockStatic(RSAEncrypt.class)) {
        // Given
        String publicKey = "testPublicKey";
        String privateKey = java.util.Base64.getEncoder().encodeToString("testPrivateKey".getBytes());
        User user = createUser(1L, "관리자", "admin", "encodedPw", false);
        RedisRsa rsa = RedisRsa.builder().publicKey(publicKey).privateKey(privateKey).build();
        LoginRequest loginRequest = LoginRequest.builder()
            .username("admin").password("encryptedPw").publicKey(publicKey).build();

        given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
        given(rsaService.findByPublicKey(publicKey)).willReturn(rsa);
        rsaMock.when(() -> RSAEncrypt.getDecryptMessage(any(String.class), any(byte[].class))).thenReturn("plainPw");
        given(passwordEncoder.matches("plainPw", "encodedPw")).willReturn(true);

        // When & Then
        assertThatThrownBy(() -> userService.login(loginRequest))
            .isInstanceOf(BadCredentialsException.class);
      }
    }
  }

  @Nested
  @DisplayName("makeToken")
  class MakeToken {

    @Test
    @DisplayName("UserResponse로 토큰 문자열을 반환한다")
    void makeToken_validUserResponse_returnsToken() {
      // Given
      UserResponse userResponse = UserResponse.builder()
          .id(1L).name("관리자").username("admin").isEnabled(true).build();
      User user = createUser(1L, "관리자", "admin", null, true);
      String expectedToken = "jwt.token.string";
      given(userDtoMapper.toDomain(userResponse)).willReturn(user);
      given(jwtTokenProvider.createToken(user)).willReturn(expectedToken);

      // When
      String result = userService.makeToken(userResponse);

      // Then
      assertThat(result).isEqualTo(expectedToken);
    }
  }

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("정상 요청이면 UserResponse를 반환한다")
    void create_validRequest_returnsUserResponse() {
      // Given
      UserCreate userCreate = UserCreate.builder()
          .name("관리자").username("admin").password("password123")
          .authorities(Set.of(AuthorityName.ROLE_ADMIN))
          .build();

      User tempUser = mock(User.class);
      Authority auth = Authority.builder().name(AuthorityName.ROLE_ADMIN).build();
      Set<Authority> authorities = new HashSet<>();
      authorities.add(auth);
      given(tempUser.getName()).willReturn("관리자");
      given(tempUser.getUsername()).willReturn("admin");
      given(tempUser.getPassword()).willReturn("encodedPassword");
      given(tempUser.getAuthorities()).willReturn(authorities);

      UserResponse expected = UserResponse.builder()
          .id(1L).name("관리자").username("admin").isEnabled(true).build();

      given(userDtoMapper.toDomain(userCreate)).willReturn(mock(User.class));
      given(passwordEncoder.encode("password123")).willReturn("encodedPassword");
      given(userDtoMapper.createUserWithEncodedPassword(any(), any())).willReturn(tempUser);
      given(authorityRepository.findByName(AuthorityName.ROLE_ADMIN)).willReturn(Optional.of(auth));
      given(userRepository.save(any())).willReturn(tempUser);
      given(userDtoMapper.toResponse(tempUser)).willReturn(expected);

      // When
      UserResponse result = userService.create(userCreate);

      // Then
      assertThat(result).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("loadUserByUsername")
  class LoadUserByUsername {

    @Test
    @DisplayName("존재하는 사용자명으로 조회하면 SecurityUser를 반환한다")
    void loadUserByUsername_existingUser_returnsSecurityUser() {
      // Given
      User user = createUser(1L, "관리자", "admin", "encodedPw", true);
      SecurityUser securityUser = SecurityUser.builder()
          .id(1L).name("관리자").username("admin").password("encodedPw").isEnabled(true)
          .authorities(Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
          .build();
      given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));
      given(userDtoMapper.toSecurityUser(user)).willReturn(securityUser);

      // When
      var result = userService.loadUserByUsername("admin");

      // Then
      assertThat(result).isEqualTo(securityUser);
    }

    @Test
    @DisplayName("존재하지 않는 사용자명으로 조회하면 DataNotFoundException이 발생한다")
    void loadUserByUsername_nonExistingUser_throwsDataNotFoundException() {
      // Given
      given(userRepository.findByUsername("unknown")).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> userService.loadUserByUsername("unknown"))
          .isInstanceOf(DataNotFoundException.class);
    }
  }
}

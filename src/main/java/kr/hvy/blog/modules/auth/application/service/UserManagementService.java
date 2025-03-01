package kr.hvy.blog.modules.auth.application.service;

import kr.hvy.blog.infra.security.JwtTokenProvider;
import kr.hvy.blog.modules.auth.adapter.out.redis.RedisRsa;
import kr.hvy.blog.modules.auth.application.port.in.UserManagementUseCase;
import kr.hvy.blog.modules.auth.application.port.out.RsaManagementUseCase;
import kr.hvy.blog.modules.auth.application.port.out.UserManagementPort;
import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.blog.modules.auth.domain.UserMapper;
import kr.hvy.blog.modules.auth.domain.UserService;
import kr.hvy.blog.modules.auth.domain.dto.LoginRequest;
import kr.hvy.blog.modules.auth.domain.dto.RsaKeyResponse;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.layer.UseCase;
import kr.hvy.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@UseCase
@RequiredArgsConstructor
public class UserManagementService implements UserManagementUseCase, UserDetailsService {


  private final JwtTokenProvider jwtTokenProvider;
  private final UserMapper userMapper;
  private final UserManagementPort userManagementPort;
  private final UserService userService;
  private final RsaManagementUseCase rsaManagementUseCase;

  @Override
  public RsaKeyResponse getRsaKey() {
    String rsaKey = rsaManagementUseCase.getRandomRsaPublicKey();
    return RsaKeyResponse.builder().publicKey(rsaKey).build();
  }

  @Override
  public UserResponse getProfile() {
    String loginId = SecurityUtils.getUsername();
    User user = userManagementPort.findByUsername(loginId);
    return userMapper.toResponse(user);
  }

  public UserResponse login(LoginRequest loginRequest) {
    User user = userManagementPort.findByUsername(loginRequest.getUsername());
    RedisRsa rsa = rsaManagementUseCase.findByPublicKey(loginRequest.getPublicKey());
    userService.login(user, loginRequest, rsa);
    return userMapper.toResponse(user);
  }

  @Override
  public String makeToken(UserResponse userResponse) {
    User user = userMapper.toDomain(userResponse);
    return jwtTokenProvider.createToken(user);
  }

  @Override
  public UserResponse create(UserCreate userCreate) {
    User user = userMapper.toDomain(userCreate);
    User createUser = userService.createUser(user, userCreate);
    User savedUser = userManagementPort.create(createUser);
    return userMapper.toResponse(savedUser);
  }

  @Override
  public UserResponse update(Long aLong, Void updateDto) {
    return UserManagementUseCase.super.update(aLong, updateDto);
  }

  @Override
  public DeleteResponse<Long> delete(Long id) {
    UserManagementUseCase.super.delete(id);
    return DeleteResponse.<Long>builder().id(id).build();
  }

  @Override
  public UserResponse findById(Long id) {
    return UserManagementUseCase.super.findById(id);
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    User user = userManagementPort.findByUsername(username);
    return userMapper.toSecurityUser(user);
  }


}

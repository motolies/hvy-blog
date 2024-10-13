package kr.hvy.blog.modules.auth.application.service;

import kr.hvy.blog.infra.security.JwtTokenProvider;
import kr.hvy.blog.modules.auth.application.port.in.UserManagementUseCase;
import kr.hvy.blog.modules.auth.application.port.out.UserManagementPort;
import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.blog.modules.auth.domain.UserMapper;
import kr.hvy.blog.modules.auth.domain.dto.LoginRequest;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import kr.hvy.blog.modules.auth.domain.specification.UserCreateSpecification;
import kr.hvy.blog.modules.auth.domain.specification.UserLoginSpecification;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

@UseCase
@RequiredArgsConstructor
public class UserManagementService implements UserManagementUseCase, UserDetailsService {

  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final UserMapper userMapper;
  private final UserManagementPort userManagementPort;

  public UserResponse login(LoginRequest loginRequest) {
    User user = userManagementPort.findByUsername(loginRequest.getUsername());

    if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
      throw new IllegalArgumentException("Password does not match.");
    }

    UserLoginSpecification userLoginSpecification = new UserLoginSpecification();
    if(userLoginSpecification.not().isSatisfiedBy(user)){
      throw new IllegalStateException("사용할 수 없는 사용자 입니다.");
    }
    return userMapper.toResponse(user);
  }

  @Override
  public String makeToken(UserResponse userResponse) {
    User user = userMapper.toDomain(userResponse);
    return jwtTokenProvider.createToken(user);
  }

  @Override
  public UserResponse create(UserCreate createDto) {
    createDto.setPassword(passwordEncoder.encode(createDto.getPassword()));
    User user = userMapper.toDomain(createDto);
    UserCreateSpecification userCreateSpecification = new UserCreateSpecification();
    if(userCreateSpecification.not().isSatisfiedBy(user)){
      throw new IllegalArgumentException("잘못된 파라미터를 사용하여 사용자를 생성할 수 없습니다.");
    }
    User savedUser = userManagementPort.create(user);
    return userMapper.toResponse(savedUser);
  }

  @Override
  public UserResponse update(Long aLong, Void updateDto) {
    return UserManagementUseCase.super.update(aLong, updateDto);
  }

  @Override
  public void delete(Long aLong) {
    UserManagementUseCase.super.delete(aLong);
  }

  @Override
  public UserResponse findById(Long aLong) {
    return UserManagementUseCase.super.findById(aLong);
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    User user = userManagementPort.findByUsername(username);
    return userMapper.toSecurityUser(user);
  }
}

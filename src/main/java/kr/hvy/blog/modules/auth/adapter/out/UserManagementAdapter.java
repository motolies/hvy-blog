package kr.hvy.blog.modules.auth.adapter.out;

import jakarta.transaction.Transactional;
import kr.hvy.blog.modules.auth.adapter.out.entity.AuthorityEntity;
import kr.hvy.blog.modules.auth.adapter.out.entity.UserEntity;
import kr.hvy.blog.modules.auth.adapter.out.persistence.JpaAuthorityRepository;
import kr.hvy.blog.modules.auth.adapter.out.persistence.JpaUserRepository;
import kr.hvy.blog.modules.auth.application.port.out.UserManagementPort;
import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.blog.modules.auth.domain.UserMapper;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class UserManagementAdapter implements UserManagementPort {

  private final UserMapper userMapper;
  private final JpaUserRepository jpaUserRepository;
  private final JpaAuthorityRepository jpaAuthorityRepository;

  @Override
  public User findByUsername(String username) {
    UserEntity userEntity = jpaUserRepository.findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("User not found."));
    return userMapper.toDomain(userEntity);
  }

  @Override
  @Transactional
  public User create(User user) {
    UserEntity userEntity = userMapper.toEntity(user);
    userEntity.getAuthorities().forEach(tempAuth -> {
      AuthorityEntity authorityEntity = loadAuthority(tempAuth.getName());
      userEntity.removeAuthority(tempAuth);
      userEntity.addAuthority(authorityEntity);
    });
    UserEntity saved = jpaUserRepository.save(userEntity);
    return userMapper.toDomain(saved);
  }

  private AuthorityEntity loadAuthority(AuthorityName authority) {
    return jpaAuthorityRepository.findByName(authority)
        .orElse(AuthorityEntity.builder().name(authority).build());
  }

}

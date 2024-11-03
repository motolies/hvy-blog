package kr.hvy.blog.modules.auth.domain;

import java.util.Set;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.auth.framework.out.entity.AuthorityEntity;
import kr.hvy.blog.modules.auth.framework.out.entity.UserEntity;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Mapper(componentModel = "spring")
public interface UserMapper {

  UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

  @ObjectFactory
  default User.UserBuilder createUserBuilder() {
    return User.builder();
  }

  // 6. User -> SecurityUser
  @Mapping(target = "authorities", source = "authorities")
  SecurityUser toSecurityUser(User user);

  // 7. SecurityUser -> User
  @Mapping(target = "authorities", source = "authorities")
  User fromSecurityUser(SecurityUser securityUser);


  User toDomain(UserEntity entity);

  UserEntity toEntity(User user);

  UserResponse toResponse(User user);

  // target에만 있는 속성에 기본값을 주려면 constant 사용
  @Mapping(target = "isEnabled", constant = "true")
  User toDomain(UserCreate userCreate);

  User toDomain(UserResponse userResponse);

  // AuthorityName -> GrantedAuthority
  default GrantedAuthority mapAuthorityNameToGrantedAuthority(AuthorityName authorityName) {
    return new SimpleGrantedAuthority(authorityName.name());
  }

  // GrantedAuthority -> AuthorityName
  default AuthorityName mapGrantedAuthorityToAuthorityName(GrantedAuthority grantedAuthority) {
    return AuthorityName.valueOf(grantedAuthority.getAuthority());
  }

  // Set<AuthorityName> -> Set<GrantedAuthority>
  default Set<GrantedAuthority> mapAuthorityNamesToGrantedAuthorities(Set<AuthorityName> authorityNames) {
    if (authorityNames == null) {
      return null;
    }
    return authorityNames.stream()
        .map(this::mapAuthorityNameToGrantedAuthority)
        .collect(Collectors.toSet());
  }

  // Set<GrantedAuthority> -> Set<AuthorityName>
  default Set<AuthorityName> mapGrantedAuthoritiesToAuthorityNames(Set<GrantedAuthority> grantedAuthorities) {
    if (grantedAuthorities == null) {
      return null;
    }
    return grantedAuthorities.stream()
        .map(this::mapGrantedAuthorityToAuthorityName)
        .collect(Collectors.toSet());
  }

  // AuthorityName -> AuthorityEntity
  default AuthorityEntity mapAuthorityNameToAuthorityEntity(AuthorityName authorityName) {
    if (authorityName == null) {
      return null;
    }
    return AuthorityEntity.builder()
        .name(authorityName)
        .build();
  }

  // AuthorityEntity -> AuthorityName
  default AuthorityName mapAuthorityEntityToAuthorityName(AuthorityEntity authorityEntity) {
    if (authorityEntity == null) {
      return null;
    }
    return authorityEntity.getName();
  }

  // Set<AuthorityName> -> Set<AuthorityEntity>
  default Set<AuthorityEntity> mapAuthorityNamesToAuthorityEntities(Set<AuthorityName> authorityNames) {
    if (authorityNames == null) {
      return null;
    }
    return authorityNames.stream()
        .map(this::mapAuthorityNameToAuthorityEntity)
        .collect(Collectors.toSet());
  }

  // Set<AuthorityEntity> -> Set<AuthorityName>
  default Set<AuthorityName> mapAuthorityEntitiesToAuthorityNames(Set<AuthorityEntity> authorityEntities) {
    if (authorityEntities == null) {
      return null;
    }
    return authorityEntities.stream()
        .map(this::mapAuthorityEntityToAuthorityName)
        .collect(Collectors.toSet());
  }

}

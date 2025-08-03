package kr.hvy.blog.modules.auth.mapper;

import java.util.Set;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.auth.application.dto.UserCreate;
import kr.hvy.blog.modules.auth.application.dto.UserResponse;
import kr.hvy.blog.modules.auth.domain.SecurityUser;

import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.entity.Authority;
import kr.hvy.blog.modules.auth.domain.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import org.mapstruct.factory.Mappers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Mapper(componentModel = "spring")
public interface UserDtoMapper {

  UserDtoMapper INSTANCE = Mappers.getMapper(UserDtoMapper.class);

  // User -> SecurityUser
  @Mapping(target = "authorities", source = "authorities")
  SecurityUser toSecurityUser(User user);

  // SecurityUser -> User
  @Mapping(target = "authorities", source = "authorities")
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "password", ignore = true)
  User fromSecurityUser(SecurityUser securityUser);

  UserResponse toResponse(User user);

  // target에만 있는 속성에 기본값을 주려면 constant 사용
  @Mapping(target = "isEnabled", constant = "true")
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "password", ignore = true)
  User toDomain(UserCreate userCreate);

  @Mapping(target = "password", ignore = true)
  User toDomain(UserResponse userResponse);

  // 기존 User 정보를 복사하고 인코딩된 패스워드를 설정
  @Mapping(target = "password", source = "encodedPassword")
  User createUserWithEncodedPassword(User sourceUser, String encodedPassword);

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
  default Authority mapAuthorityNameToAuthorityEntity(AuthorityName authorityName) {
    if (authorityName == null) {
      return null;
    }
    return Authority.builder()
        .name(authorityName)
        .build();
  }

  // AuthorityEntity -> AuthorityName
  default AuthorityName mapAuthorityEntityToAuthorityName(Authority authority) {
    if (authority == null) {
      return null;
    }
    return authority.getName();
  }

  // Set<AuthorityName> -> Set<AuthorityEntity>
  default Set<Authority> mapAuthorityNamesToAuthorityEntities(Set<AuthorityName> authorityNames) {
    if (authorityNames == null) {
      return null;
    }
    return authorityNames.stream()
        .map(this::mapAuthorityNameToAuthorityEntity)
        .collect(Collectors.toSet());
  }

  // Set<AuthorityEntity> -> Set<AuthorityName>
  default Set<AuthorityName> mapAuthorityEntitiesToAuthorityNames(Set<Authority> authorityEntities) {
    if (authorityEntities == null) {
      return null;
    }
    return authorityEntities.stream()
        .map(this::mapAuthorityEntityToAuthorityName)
        .collect(Collectors.toSet());
  }

}

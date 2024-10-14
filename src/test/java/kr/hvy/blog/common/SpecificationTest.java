package kr.hvy.blog.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.specification.UserCreateSpecification;
import kr.hvy.blog.modules.auth.domain.specification.UserLoginSpecification;
import kr.hvy.common.specification.Specification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
public class SpecificationTest {

  private Specification<User> userCreateSpecification = new UserCreateSpecification();
  private Specification<User> userLoginSpecification = new UserLoginSpecification();


  @Test
  @DisplayName("And 명세패턴 성공 케이스")
  public void success_case_and() {
    // given
    User user = createValidUser();

    // when
    boolean result = userCreateSpecification
        .and(userLoginSpecification)
        .isSatisfiedBy(user);

    // then
    assertTrue(result, "명세가 만족해야 합니다.");
  }

  @Test
  @DisplayName("Or 명세패턴 성공 케이스")
  public void success_case_or() {
    // given
    User user = createUserDisable();

    // when
    boolean result = userCreateSpecification
        .or(userLoginSpecification)
        .isSatisfiedBy(user);

    // then
    assertTrue(result, "명세가 만족해야 합니다.");
  }

  @Test
  @DisplayName("Not 명세패턴 성공 케이스")
  public void success_case_not() {
    // given
    User user = createUserDisable();

    // when
    boolean result = userLoginSpecification
        .not()
        .isSatisfiedBy(user);

    // then
    assertTrue(result, "명세가 만족해야 합니다.");
  }

  @Test
  @DisplayName("isSatisfiedBy 관계없이 ErrorMessage 수집")
  public void validate_option_case() {
    // given
    User user = createUserDisable();

    // when
    Optional<List<String>> result = userCreateSpecification
        .or(userLoginSpecification)
        .validateOptional(user);

    // then
    assertTrue(result.get().size() == 1, "하나의 오류메시지만 있어야 합니다.");
  }

  // 유효한 사용자
  private User createValidUser() {
    return User.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .isEnabled(true)
        .build();
  }

  // 사용여부가 없는 사용자
  private User createUserNotEnable() {
    return User.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .build();
  }

  // 사용이 불가한 사용자
  private User createUserDisable() {
    return User.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .isEnabled(false)
        .build();
  }


}

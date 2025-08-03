package kr.hvy.blog.common;

import static kr.hvy.common.specification.Specification.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.auth.domain.entity.User;
import kr.hvy.blog.modules.auth.domain.entity.Authority;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.application.specification.UserCreateSpecification;
import kr.hvy.blog.modules.auth.application.specification.UserLoginSpecification;
import kr.hvy.common.exception.SpecificationException;
import kr.hvy.common.specification.Specification;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


public class SpecificationTest {

  private Specification<User> userCreateSpecification = new UserCreateSpecification();
  private Specification<User> userLoginSpecification = new UserLoginSpecification();

  /**
   * 테스트를 위한 커스텀 명세 추가
   */
  public class CustomMessageSpecification implements Specification<User> {

    private final String customMessage;

    public CustomMessageSpecification(String customMessage) {
      this.customMessage = customMessage;
    }

    @Override
    public boolean isSatisfiedBy(User user) {
      return ObjectUtils.isNotEmpty(user.getIsEnabled()) ? user.getIsEnabled() : false;
    }

    @Override
    public String getErrorMessage() {
      return "[Error Detail] : " + customMessage;
    }
  }


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
  @DisplayName("and & Not 명세패턴 성공 케이스")
  public void success_case_and_not() {
    // given
    User user = createUserDisable();

    // when
    boolean result = userCreateSpecification
        .and(not(userLoginSpecification))
        .isSatisfiedBy(user);

    // then
    assertTrue(result, "명세가 만족해야 합니다.");
  }

  @Test
  @DisplayName("isSatisfiedBy가 틀리면 exception 발생 테스트")
  public void success_case_isSatisfiedBy_exception() {
    // given
    User user = createUserNotEnableAndPassword();

    // When & Then
    Exception exception = assertThrows(SpecificationException.class, () -> {
      userCreateSpecification
          .or(userLoginSpecification)
          .validateException(user);
//    }, "예외가 발생해야 합니다.");
    });

    // then
    assertEquals(SpecificationException.class, exception.getClass(), exception.getMessage());
  }

  @Test
  @DisplayName("isSatisfiedBy 관계없이 ErrorMessage 수집")
  public void validate_option_case() {
    // given
    User user = createUserDisable();

    // when
    Optional<List<String>> result = userCreateSpecification
        .or(userLoginSpecification)
        .validateOptionalMessages(user);

    // then
    assertTrue(result.get().size() == 1, "하나의 오류메시지만 있어야 합니다.");
  }

  @Test
  @DisplayName("명세패턴의 커스텀 오류 메시지 추가 - validateException")
  public void custom_message_validateException() {
    // given
    User user = createUserDisable();
    CustomMessageSpecification customMessageSpecification = new CustomMessageSpecification("이건 무조건 안돼!");

    // When & Then
    Exception exception = assertThrows(SpecificationException.class, () -> {
      customMessageSpecification
          .validateException(user);
    });

    // then
    assertEquals(SpecificationException.class, exception.getClass(), exception.getMessage());
  }

  @Test
  @DisplayName("명세패턴의 커스텀 오류 메시지 추가 - validateOptionalMessages")
  public void custom_message_validateOptionalMessages() {
    // given
    User user = createUserDisable();
    CustomMessageSpecification customMessageSpecification = new CustomMessageSpecification("이건 무조건 안돼!");

    // when
    Optional<List<String>> result = customMessageSpecification
        .validateOptionalMessages(user);

    // then
    assertEquals(1, result.get().size(), "하나의 오류메시지만 있어야 합니다.");
  }

  // 유효한 사용자
  private User createValidUser() {
    Authority authority = Authority.builder()
        .name(AuthorityName.ROLE_USER)
        .build();
    return User.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(authority))
        .isEnabled(true)
        .build();
  }

  // 사용여부가 없는 사용자
  private User createUserNotEnableAndPassword() {
    Authority authority = Authority.builder()
        .name(AuthorityName.ROLE_USER)
        .build();
    return User.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .authorities(Set.of(authority))
        .build();
  }

  // 사용이 불가한 사용자
  private User createUserDisable() {
    Authority authority = Authority.builder()
        .name(AuthorityName.ROLE_USER)
        .build();
    return User.builder()
        .id(1L)
        .name("my name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(authority))
        .isEnabled(false)
        .build();
  }


}

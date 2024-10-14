package kr.hvy.blog.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import kr.hvy.blog.modules.auth.application.port.in.UserManagementUseCase;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")  // 'application-test.yml' 사용
public class UserManagementTest {

  @Autowired
  private UserManagementUseCase userManagementUseCase;

  @Test
  @DisplayName("사용자 추가 - 성공")
  void createUser() {
    // Given
    UserCreate userCreate = UserCreate.builder()
        .name("name")
        .username("hi")
        .password("bye")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .build();

    // When
    UserResponse userResponse = userManagementUseCase.create(userCreate);

    // Then
    assertNotNull(userResponse.getId(), "사용자ID는 null 일 수 없습니다.");
    assertEquals("hi", userResponse.getUsername(), "사용자 이름이 다릅니다.");
    assertEquals(Set.of(AuthorityName.ROLE_USER), userResponse.getAuthorities(), "사용자 권한이 다릅니다.");
    assertEquals(true, userResponse.getIsEnabled(), "사용자 상태는 사용가능이어야 합니다.");
  }

  @Test
  @DisplayName("사용자 추가 - 중복된 사용자 이름으로 실패")
  void createUserDuplicateUsername() {
    // Given
    UserCreate userCreate = UserCreate.builder()
        .name("name1")
        .username("duplicateUser")
        .password("password123")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .build();

    userManagementUseCase.create(userCreate);

    // When & Then
    UserCreate duplicateUserCreate = UserCreate.builder()
        .name("name2")
        .username("duplicateUser")
        .password("password456")
        .authorities(Set.of(AuthorityName.ROLE_ADMIN))
        .build();

    Exception exception = assertThrows(DataIntegrityViolationException.class, () -> {
      userManagementUseCase.create(duplicateUserCreate);
    });

    assertEquals(DataIntegrityViolationException.class, exception.getClass(), "중복된 아이디 체크가 예상치 못한 오류에서 생성되었습니다.");

//    DataIntegrityViolationException으로 체크하지 않고 Exception 으로 체크한다면 메시지로도 가능?
//    Exception ex = assertThrows(Exception.class, () -> {
//      userManagementUseCase.create(duplicateUserCreate);
//    });
//    assertTrue(ex.getMessage().contains("insert") && ex.getMessage().contains("duplicateUser"), "예외 메시지가 중복된 사용자 이름을 포함해야 합니다.");
  }

  // 필수 필드 누락 테스트
  @Test
  @DisplayName("사용자 추가 - 필수 필드 누락으로 실패")
  void createUserMissingFields() {
    // Given
    UserCreate userCreate = UserCreate.builder()
        .name(null)
        .username("userWithoutName")
        .password("password123")
        .authorities(Set.of(AuthorityName.ROLE_USER))
        .build();

    // When & Then
    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      userManagementUseCase.create(userCreate);
    });

    assertEquals(IllegalArgumentException.class, exception.getClass(), "사용자 이름은 필수 입니다.");

  }
}

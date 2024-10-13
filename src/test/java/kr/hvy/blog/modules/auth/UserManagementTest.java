package kr.hvy.blog.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Set;
import kr.hvy.blog.modules.auth.application.port.in.UserManagementUseCase;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")  // 'application-test.yml' 사용
public class UserManagementTest {

  @Autowired
  private UserManagementUseCase userManagementUseCase;

  @Test
  @DisplayName("사용자 추가")
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

}

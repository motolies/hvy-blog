package kr.hvy.blog.modules.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.modules.auth.application.port.in.UserManagementUseCase;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import kr.hvy.blog.modules.post.application.port.in.PostManagementUseCase;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")  // 'application-test.yml' 사용
public class PostManagementTest extends AbstractTestContainers {

  @Autowired
  private PostManagementUseCase postManagementUseCase;

  @Test
  @DisplayName("포스트 추가 - 성공")
  void createPost() {
    // Given
    PostCreate postCreate = PostCreate.builder().build();

    // When
    PostResponse postResponse = postManagementUseCase.create(postCreate);

    // Then
    assertNotNull(postResponse.getId(), "포스트ID는 null 일 수 없습니다.");
    assertEquals(PostStatus.TEMP, postResponse.getStatus(), "사용자 권한이 다릅니다.");
  }

}
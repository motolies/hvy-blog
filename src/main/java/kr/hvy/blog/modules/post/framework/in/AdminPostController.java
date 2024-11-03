package kr.hvy.blog.modules.post.framework.in;

import kr.hvy.blog.modules.post.application.port.in.PostManagementUseCase;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post/admin")
@RequiredArgsConstructor
public class AdminPostController {

  private final PostManagementUseCase postManagementUseCase;

  @PostMapping
  public ResponseEntity<?> create() {
    return ResponseEntity
        .ok()
        .body(postManagementUseCase.create(PostCreate.builder().build()));
  }
}

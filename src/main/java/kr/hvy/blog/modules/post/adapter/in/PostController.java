package kr.hvy.blog.modules.post.adapter.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.usecase.PostManagementUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostController {

  private final PostManagementUseCase postManagementUseCase;

  @PostMapping
  public ResponseEntity<?> create() {
    return ResponseEntity
        .ok()
        .body(postManagementUseCase.create(PostCreate.builder().build()));
  }
}

package kr.hvy.blog.modules.post.framework.in;

import kr.hvy.blog.modules.post.application.port.in.PostManagementUseCase;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post/admin")
@RequiredArgsConstructor
public class AdminPostController {

  private final PostManagementUseCase postManagementUseCase;

  /**
   * 신규 포스트 작성 할 때
   */
  @PostMapping
  public ResponseEntity<?> create() {
    return ResponseEntity
        .ok()
        .body(postManagementUseCase.create(PostCreate.builder().build()));
  }

  /**
   * 포스트 수정
   */
  @PutMapping("/{id}")
  public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PostUpdate postUpdate) {
    return ResponseEntity
        .ok()
        .body(postManagementUseCase.update(id, postUpdate));
  }

  /**
   * 포스트 삭제
   */
  public ResponseEntity<?> delete(Long postId) {
    throw new NotImplementedException("Not implemented yet");
  }

}

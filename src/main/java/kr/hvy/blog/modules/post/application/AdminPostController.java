package kr.hvy.blog.modules.post.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.post.application.dto.PostCreate;
import kr.hvy.blog.modules.post.application.dto.PostPublicRequest;
import kr.hvy.blog.modules.post.application.dto.PostUpdate;
import kr.hvy.blog.modules.post.application.service.PostService;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

  private final PostService postService;

  /**
   * 신규 포스트 작성 할 때
   */
  @PostMapping
  public ResponseEntity<?> create() {
    return ResponseEntity
        .ok()
        .body(postService.create(PostCreate.builder().build()));
  }

  /**
   * 포스트 수정
   */
  @PutMapping("/{id}")
  public ResponseEntity<?> update(@PathVariable Long id, @RequestBody @Valid PostUpdate postUpdate) {
    return ResponseEntity
        .ok()
        .body(postService.update(id, postUpdate));
  }

  /**
   * 포스트 삭제
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(@PathVariable Long id) {
    return ResponseEntity
        .ok()
        .body(postService.delete(id));
  }

  /**
   * 메인 포스트로 지정
   */
  @PostMapping("/main/{postId}")
  public ResponseEntity<?> setMainPost(@PathVariable Long postId) {
    postService.setMainPost(postId);
    return ResponseEntity.ok().build();
  }

  /**
   * 포스트 공개/비공개 설정
   */
  @PostMapping("/public")
  public ResponseEntity<?> setPublicPost(@RequestBody PostPublicRequest contentPublicRequest) {
    return ResponseEntity
        .ok()
        .body(postService.setPostVisible(contentPublicRequest));
  }

  /**
   * 포스트에 태크 추가
   */
  @PostMapping("/{postId}/tag")
  public ResponseEntity<?> addTagToPost(@PathVariable Long postId, @RequestBody TagCreate tagCreate) {
    return ResponseEntity
        .ok()
        .body(postService.addPostTag(postId, tagCreate));
  }

  /**
   * 포스트에 태그 삭제
   */
  @DeleteMapping("/{postId}/tag/{tagId}")
  public ResponseEntity<?> deletePostTag(@PathVariable Long postId, @PathVariable Long tagId) {
    return ResponseEntity
        .ok()
        .body(postService.deletePostTag(postId, tagId));
  }

}

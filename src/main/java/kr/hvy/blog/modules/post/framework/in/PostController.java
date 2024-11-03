package kr.hvy.blog.modules.post.framework.in;

import kr.hvy.blog.modules.post.application.port.in.PostManagementUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostController {

  private final PostManagementUseCase postManagementUseCase;

//  @PostMapping
//  public ResponseEntity<?> create() {
//    return ResponseEntity
//        .ok()
//        .body(postManagementUseCase.create(PostCreate.builder().build()));
//  }
}

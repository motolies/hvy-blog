package kr.hvy.blog.modules.post.adapter.in;

import kr.hvy.blog.modules.post.application.port.in.PostSearchEnginePublicUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post/search-engine")
@RequiredArgsConstructor
public class PostSearchEngineController {

  private final PostSearchEnginePublicUseCase postSearchEnginePublicUseCase;

  /**
   * main post의 검색엔진 표기
   */
  @GetMapping
  public ResponseEntity<?> getPostSearchEngine() {
    return ResponseEntity
        .status(HttpStatus.OK)
        .body(postSearchEnginePublicUseCase.mainPostSearchEngines());
  }


}

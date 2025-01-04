package kr.hvy.blog.modules.tag.framework.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.tag.application.port.in.TagPublicUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tag")
@RequiredArgsConstructor
public class TagController {
  private final TagPublicUseCase tagPublicUseCase;

  @GetMapping("/all")
    public ResponseEntity<?> allTags() {
    return ResponseEntity
        .ok()
        .body(tagPublicUseCase.getAllTags());
  }

  @GetMapping
  public ResponseEntity<?> search(@RequestParam(defaultValue = "") String name) {
    return ResponseEntity
        .ok()
        .body(tagPublicUseCase.findByNameContainingOrderByName(name));
  }

}

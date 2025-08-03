package kr.hvy.blog.modules.tag.application;

import kr.hvy.blog.modules.tag.application.service.TagService;
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

  private final TagService tagService;

  @GetMapping("/all")
  public ResponseEntity<?> allTags() {
    return ResponseEntity
        .ok()
        .body(tagService.getAllTags());
  }

  @GetMapping
  public ResponseEntity<?> search(@RequestParam(defaultValue = "") String name) {
    return ResponseEntity
        .ok()
        .body(tagService.findByNameContainingOrderByName(name));
  }

}

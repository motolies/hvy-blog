package kr.hvy.blog.modules.tag.application;

import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tag/admin")
@RequiredArgsConstructor
public class AdminTagController {

  private final TagService tagService;

  @PostMapping
  public ResponseEntity<?> create(@RequestBody TagCreate tagCreate) {
    return ResponseEntity
        .ok()
        .body(tagService.create(tagCreate));
  }

  @DeleteMapping("/{tagId}")
  public ResponseEntity<?> delete(@PathVariable Long tagId) {
    return ResponseEntity
        .ok()
        .body(tagService.delete(tagId));
  }

}

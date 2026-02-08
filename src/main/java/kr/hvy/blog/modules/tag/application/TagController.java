package kr.hvy.blog.modules.tag.application;

import java.util.List;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.application.service.TagService;
import lombok.RequiredArgsConstructor;
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
  public List<TagResponse> allTags() {
    return tagService.getAllTags();
  }

  @GetMapping
  public List<TagResponse> search(@RequestParam(defaultValue = "") String name) {
    return tagService.findByNameContainingOrderByName(name);
  }

}

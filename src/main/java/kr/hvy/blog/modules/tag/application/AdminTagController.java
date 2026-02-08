package kr.hvy.blog.modules.tag.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.application.service.TagService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tag/admin")
@RequiredArgsConstructor
public class AdminTagController {

  private final TagService tagService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TagResponse create(@RequestBody @Valid TagCreate tagCreate) {
    return tagService.create(tagCreate);
  }

  @DeleteMapping("/{tagId}")
  public DeleteResponse<Long> delete(@PathVariable Long tagId) {
    return tagService.delete(tagId);
  }

}

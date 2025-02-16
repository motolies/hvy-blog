package kr.hvy.blog.modules.tag.adapter.in;

import kr.hvy.blog.modules.common.dto.DeleteResponse;
import kr.hvy.blog.modules.tag.application.port.in.TagManagementUseCase;
import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
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

  private final TagManagementUseCase tagManagementUseCase;

  @PostMapping
  public ResponseEntity<?> create(@RequestBody TagCreate tagCreate) {
    return ResponseEntity
        .ok()
        .body(tagManagementUseCase.create(tagCreate));
  }

  @DeleteMapping("/{tagId}")
  public ResponseEntity<?> delete(@PathVariable Long tagId) {

    DeleteResponse<Long> response = DeleteResponse.<Long>builder()
        .id(tagManagementUseCase.delete(tagId))
        .build();

    return ResponseEntity
        .ok()
        .body(response);
  }

}

package kr.hvy.blog.modules.category.adapter.in;

import kr.hvy.blog.modules.category.application.port.in.CategoryManagementUseCase;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.dto.CategoryUpdate;
import kr.hvy.blog.modules.common.dto.DeleteResponse;
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
@RequestMapping("/api/category/admin")
@RequiredArgsConstructor
public class AdminCategoryController {

  private final CategoryManagementUseCase categoryManagementUseCase;

  @PostMapping
  public ResponseEntity<?> create(@RequestBody CategoryCreate categoryCreate) {
    return ResponseEntity
        .ok()
        .body(categoryManagementUseCase.create(categoryCreate));
  }

  @DeleteMapping("/{categoryId}")
  public ResponseEntity<?> delete(@PathVariable String categoryId) {
    DeleteResponse<String> response = DeleteResponse.<String>builder()
        .id(categoryManagementUseCase.delete(categoryId))
        .build();
    return ResponseEntity
        .ok()
        .body(response);
  }

  @PutMapping("/{categoryId}")
  public ResponseEntity<?> update(@PathVariable String categoryId, @RequestBody CategoryUpdate categoryUpdate) {
    return ResponseEntity
        .ok()
        .body(categoryManagementUseCase.update(categoryId, categoryUpdate));
  }

}

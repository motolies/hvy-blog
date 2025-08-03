package kr.hvy.blog.modules.category.application;

import kr.hvy.blog.modules.category.application.dto.CategoryCreate;
import kr.hvy.blog.modules.category.application.dto.CategoryUpdate;
import kr.hvy.blog.modules.category.application.service.CategoryService;
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

  private final CategoryService categoryService;

  @PostMapping
  public ResponseEntity<?> create(@RequestBody CategoryCreate categoryCreate) {
    return ResponseEntity
        .ok()
        .body(categoryService.create(categoryCreate));
  }

  @DeleteMapping("/{categoryId}")
  public ResponseEntity<?> delete(@PathVariable String categoryId) {
    return ResponseEntity
        .ok()
        .body(categoryService.delete(categoryId));
  }

  @PutMapping("/{categoryId}")
  public ResponseEntity<?> update(@PathVariable String categoryId, @RequestBody CategoryUpdate categoryUpdate) {
    return ResponseEntity
        .ok()
        .body(categoryService.update(categoryId, categoryUpdate));
  }

}

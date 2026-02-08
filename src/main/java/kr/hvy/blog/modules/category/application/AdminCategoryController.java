package kr.hvy.blog.modules.category.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.category.application.dto.CategoryCreate;
import kr.hvy.blog.modules.category.application.dto.CategoryResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryUpdate;
import kr.hvy.blog.modules.category.application.service.CategoryService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/category/admin")
@RequiredArgsConstructor
public class AdminCategoryController {

  private final CategoryService categoryService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CategoryResponse create(@RequestBody @Valid CategoryCreate categoryCreate) {
    return categoryService.create(categoryCreate);
  }

  @DeleteMapping("/{categoryId}")
  public DeleteResponse<String> delete(@PathVariable String categoryId) {
    return categoryService.delete(categoryId);
  }

  @PutMapping("/{categoryId}")
  public CategoryResponse update(@PathVariable String categoryId, @RequestBody @Valid CategoryUpdate categoryUpdate) {
    return categoryService.update(categoryId, categoryUpdate);
  }

}

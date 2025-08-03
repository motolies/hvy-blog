package kr.hvy.blog.modules.category.application;

import kr.hvy.blog.modules.category.application.service.CategoryPublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

  private final CategoryPublicService categoryPublicService;

  @GetMapping
  public ResponseEntity<?> getAllCategories() {
    return ResponseEntity
        .ok()
        .body(categoryPublicService.getAllCategories());
  }

  @GetMapping("/root")
  public ResponseEntity<?> getRootCategory() {
    return ResponseEntity
        .ok()
        .body(categoryPublicService.getRootCategory());
  }
}

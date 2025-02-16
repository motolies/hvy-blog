package kr.hvy.blog.modules.category.adapter.in;

import kr.hvy.blog.modules.category.application.port.in.CategoryPublicUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

  private final CategoryPublicUseCase categoryPublicUseCase;

  @GetMapping
  public ResponseEntity<?> getAllCategories() {
    return ResponseEntity
        .ok()
        .body(categoryPublicUseCase.getAllCategories());
  }

  @GetMapping("/root")
  public ResponseEntity<?> getRootCategory() {
    return ResponseEntity
        .ok()
        .body(categoryPublicUseCase.getRootCategory());
  }
}

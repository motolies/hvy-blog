package kr.hvy.blog.modules.category.application;

import java.util.List;
import kr.hvy.blog.modules.category.application.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryResponse;
import kr.hvy.blog.modules.category.application.service.CategoryPublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

  private final CategoryPublicService categoryPublicService;

  @GetMapping
  public List<CategoryFlatResponse> getAllCategories() {
    return categoryPublicService.getAllCategories();
  }

  @GetMapping("/root")
  public CategoryResponse getRootCategory() {
    return categoryPublicService.getRootCategory();
  }
}

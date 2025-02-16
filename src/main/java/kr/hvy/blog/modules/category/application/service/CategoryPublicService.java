package kr.hvy.blog.modules.category.application.service;

import java.util.List;
import kr.hvy.blog.modules.category.application.port.in.CategoryPublicUseCase;
import kr.hvy.blog.modules.category.application.port.out.CategoryManagementPort;
import kr.hvy.blog.modules.category.domain.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CategoryPublicService implements CategoryPublicUseCase {

  private final CategoryManagementPort categoryManagementPort;

  @Override
  public List<CategoryFlatResponse> getAllCategories() {
    return categoryManagementPort.findAllCategory();
  }

  @Override
  public CategoryResponse getRootCategory() {
    return categoryManagementPort.findByRoot();
  }
}

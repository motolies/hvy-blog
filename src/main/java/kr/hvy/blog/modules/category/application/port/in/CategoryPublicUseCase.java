package kr.hvy.blog.modules.category.application.port.in;

import java.util.List;
import kr.hvy.blog.modules.category.domain.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;

public interface CategoryPublicUseCase {

  List<CategoryFlatResponse> getAllCategories();

  CategoryResponse getRootCategory();
}

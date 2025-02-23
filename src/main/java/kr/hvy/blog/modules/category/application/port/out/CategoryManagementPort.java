package kr.hvy.blog.modules.category.application.port.out;

import java.util.List;
import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.category.domain.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;

public interface CategoryManagementPort {

  CategoryResponse findByRoot();

  Category save(Category category);

  String deleteById(String id);

  List<CategoryFlatResponse> findAllCategory();

  Category findById(String id);
}

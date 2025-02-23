package kr.hvy.blog.modules.category.domain.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.category.domain.CategoryConstant;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;

public class CategoryDeleteSpecification implements Specification<Category> {

  List<String> errorMessages = new ArrayList<>();


  @Override
  public boolean isSatisfiedBy(Category category) {
    errorMessages.clear();

    if (category.getId().trim().equalsIgnoreCase(CategoryConstant.ROOT_CATEGORY_ID)) {
      errorMessages.add("카테고리 ID는 ROOT는 삭제할 수 없습니다.");
    }

    if(CollectionUtils.isNotEmpty(category.getCategories())) {
      errorMessages.add("하위 카테고리가 존재합니다.");
    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}

package kr.hvy.blog.modules.category.domain.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.category.domain.CategoryConstant;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

public class CategoryCreateSpecification implements Specification<CategoryCreate> {

  List<String> errorMessages = new ArrayList<>();


  @Override
  public boolean isSatisfiedBy(CategoryCreate categoryCreate) {
    errorMessages.clear();

    if (StringUtils.isBlank(categoryCreate.getParentId())) {
      errorMessages.add("상위 카테고리는 필수항목 입니다.");
    }

    if (StringUtils.isBlank(categoryCreate.getName())) {
      errorMessages.add("카테고리명은 필수항목 입니다.");
    }

    if (StringUtils.isBlank(categoryCreate.getId())) {
      errorMessages.add("카테고리 ID는 필수항목 입니다.");
    } else {
      if (categoryCreate.getId().trim().equalsIgnoreCase(CategoryConstant.ROOT_CATEGORY_ID)) {
        errorMessages.add("카테고리 ID는 ROOT가 될 수 없습니다.");
      }
    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}

package kr.hvy.blog.modules.category.domain.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.category.domain.dto.CategoryUpdate;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

public class CategoryUpdateSpecification implements Specification<CategoryUpdate> {

  List<String> errorMessages = new ArrayList<>();


  @Override
  public boolean isSatisfiedBy(CategoryUpdate categoryUpdate) {
    errorMessages.clear();

    if (StringUtils.isBlank(categoryUpdate.getName())) {
      errorMessages.add("카테고리 이름은 필수입니다.");
    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}

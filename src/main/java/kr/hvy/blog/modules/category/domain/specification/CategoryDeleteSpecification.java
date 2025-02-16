package kr.hvy.blog.modules.category.domain.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.category.domain.CategoryConstant;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

public class CategoryDeleteSpecification implements Specification<String> {

  List<String> errorMessages = new ArrayList<>();


  @Override
  public boolean isSatisfiedBy(String categoryId) {
    errorMessages.clear();

    if (StringUtils.isBlank(categoryId)) {
      errorMessages.add("카테고리 ID는 필수항목 입니다.");
    } else {
      if (categoryId.trim().equalsIgnoreCase(CategoryConstant.ROOT_CATEGORY_ID)) {
        errorMessages.add("카테고리 ID는 ROOT는 삭제할 수 없습니다.");
      }

    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}

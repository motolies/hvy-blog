package kr.hvy.blog.modules.tag.domain.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

public class TagCreateSpecification implements Specification<TagCreate> {

  List<String> errorMessages = new ArrayList<>();

  @Override
  public boolean isSatisfiedBy(TagCreate tagCreate) {
    errorMessages.clear();

    if (StringUtils.isBlank(tagCreate.getName())) {
      errorMessages.add("태그명은 비어 있을 수 없습니다.");
    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}

package kr.hvy.blog.modules.post.application.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.post.application.dto.PostUpdate;
import kr.hvy.common.core.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

public class PostUpdateSpecification implements Specification<PostUpdate> {

  List<String> errorMessages = new ArrayList<>();

  @Override
  public boolean isSatisfiedBy(PostUpdate postUpdate) {
    errorMessages.clear();

    if (StringUtils.isBlank(postUpdate.getSubject())) {
      errorMessages.add("제목은 비어 있을 수 없습니다.");
    }
    if (StringUtils.isBlank(postUpdate.getBody())) {
      errorMessages.add("본문은 비어 있을 수 없습니다.");
    }
    if (StringUtils.isBlank(postUpdate.getCategoryId())) {
      errorMessages.add("카테고리는 필수 입니다.");
    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}

package kr.hvy.blog.modules.auth.application.specification;

import kr.hvy.blog.modules.auth.domain.entity.User;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

public class UserCreateSpecification implements Specification<User> {

  @Override
  public boolean isSatisfiedBy(User user) {
    return StringUtils.isNotBlank(user.getName())
        && StringUtils.isNotBlank(user.getUsername())
        && StringUtils.isNotBlank(user.getPassword())
        && CollectionUtils.isNotEmpty(user.getAuthorities());
  }

  @Override
  public String getErrorMessage() {
    return "사용자를 생성할 수 없습니다.";
  }
}

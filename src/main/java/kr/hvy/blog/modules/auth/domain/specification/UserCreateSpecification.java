package kr.hvy.blog.modules.auth.domain.specification;

import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

public class UserCreateSpecification implements Specification<User> {

  @Override
  public boolean isSatisfiedBy(User user) {
    return StringUtils.isNotBlank(user.getName())
        && StringUtils.isNotBlank(user.getUsername())
        && StringUtils.isNotBlank(user.getPassword())
        && CollectionUtils.isNotEmpty(user.getAuthorities());
  }
}

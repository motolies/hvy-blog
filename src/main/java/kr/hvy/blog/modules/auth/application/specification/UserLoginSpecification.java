package kr.hvy.blog.modules.auth.application.specification;

import kr.hvy.blog.modules.auth.domain.entity.User;
import kr.hvy.common.specification.Specification;
import org.apache.commons.lang3.ObjectUtils;

public class UserLoginSpecification implements Specification<User> {

  @Override
  public boolean isSatisfiedBy(User user) {
    return ObjectUtils.isNotEmpty(user.getIsEnabled()) ? user.getIsEnabled() : false;
  }

  @Override
  public String getErrorMessage() {
    return "로그인 할 수 없는 사용자 입니다.";
  }
}

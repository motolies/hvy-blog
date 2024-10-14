package kr.hvy.blog.modules.auth.domain.specification;

import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.common.specification.Specification;

public class UserLoginSpecification implements Specification<User> {

  @Override
  public boolean isSatisfiedBy(User user) {
    return user.getIsEnabled();
  }

  @Override
  public String getErrorMessage() {
    return "로그인 할 수 없는 사용자 입니다.";
  }
}

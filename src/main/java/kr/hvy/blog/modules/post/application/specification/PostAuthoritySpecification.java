package kr.hvy.blog.modules.post.application.specification;

import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.common.core.security.SecurityUtils;
import kr.hvy.common.core.specification.Specification;

public class PostAuthoritySpecification implements Specification<Post> {

  @Override
  public boolean isSatisfiedBy(Post post) {
    return SecurityUtils.hasAdminRole() || isOwner(post) || post.isPublicAccess();
  }

  private boolean isOwner(Post post) {
    // todo : 나중에 crateUser로 변경
    return false;
  }

  @Override
  public String getErrorMessage() {
    return "권한이 없습니다.";
  }
}

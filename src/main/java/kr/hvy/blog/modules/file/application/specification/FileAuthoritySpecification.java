package kr.hvy.blog.modules.file.application.specification;

import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.common.core.security.SecurityUtils;
import kr.hvy.common.core.specification.Specification;

public class FileAuthoritySpecification implements Specification<File> {

  @Override
  public boolean isSatisfiedBy(File file) {
    // 관리자 권한이 있거나
    // 파일의 소유자이거나
    // 파일과 연결된 Post가 public이거나(null이 아닌 경우)
    return SecurityUtils.hasAdminRole() || isOwner(file) || isPostPublic(file);
  }

  private boolean isOwner(File file) {
    // 파일 소유자 확인 로직 (향후 구현)
    return false;
  }

  private boolean isPostPublic(File file) {
    Post post = file.getPost();
    // Post가 없는 경우 false 반환
    if (post == null) {
      return false;
    }
    // Post가 public인지 확인
    return post.isPublicAccess();
  }

  @Override
  public String getErrorMessage() {
    return "파일에 접근할 권한이 없습니다.";
  }
}
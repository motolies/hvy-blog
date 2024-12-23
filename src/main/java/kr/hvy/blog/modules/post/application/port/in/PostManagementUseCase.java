package kr.hvy.blog.modules.post.application.port.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostPublicRequest;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface PostManagementUseCase extends CrudUseCase<Post, PostResponse, PostCreate, PostUpdate, Long> {

  void setMainPost(Long id);

  PostResponse setPostVisible(PostPublicRequest postPublicRequest);
}

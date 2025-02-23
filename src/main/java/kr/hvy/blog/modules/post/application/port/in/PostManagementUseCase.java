package kr.hvy.blog.modules.post.application.port.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostPublicRequest;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface PostManagementUseCase extends CrudUseCase<Post, PostResponse, PostCreate, PostUpdate, Long> {

  void setMainPost(Long id);

  PostResponse setPostVisible(PostPublicRequest postPublicRequest);

  TagResponse addPostTag(Long postId, TagCreate tagCreate);

  PostResponse deletePostTag(Long postId, Long tagId);

  void migration();
}

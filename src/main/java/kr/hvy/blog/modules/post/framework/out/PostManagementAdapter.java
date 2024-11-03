package kr.hvy.blog.modules.post.framework.out;

import kr.hvy.blog.modules.post.framework.out.persistence.JpaPostRepository;
import kr.hvy.blog.modules.post.framework.out.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostManagementAdapter implements PostManagementPort {

  private final PostMapper postMapper;
  private final JpaPostRepository jpaPostRepository;

  @Override
  public Post create(Post post) {
    PostEntity postEntity = jpaPostRepository.save(postMapper.toEntity(post));
    return postMapper.toDomain(postEntity);
  }
}

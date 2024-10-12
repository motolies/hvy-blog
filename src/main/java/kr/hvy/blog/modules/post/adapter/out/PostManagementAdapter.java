package kr.hvy.blog.modules.post.adapter.out;

import kr.hvy.blog.modules.post.adapter.out.persistence.JpaPostRepository;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.mapper.PostMapper;
import kr.hvy.blog.modules.post.domain.model.Post;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostManagementAdapter implements PostManagementPort {

  private final JpaPostRepository jpaPostRepository;

  @Override
  public Post create(Post post) {
    PostEntity postEntity = jpaPostRepository.save(PostMapper.toEntity(post));
    return PostMapper.toDomain(postEntity);
  }
}

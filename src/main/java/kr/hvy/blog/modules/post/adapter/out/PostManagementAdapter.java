package kr.hvy.blog.modules.post.adapter.out;

import kr.hvy.blog.modules.post.adapter.out.persistence.PostRepository;
import kr.hvy.blog.modules.post.domain.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.mapper.PostMapper;
import kr.hvy.blog.modules.post.domain.model.Post;
import kr.hvy.blog.modules.post.port.out.PostManagementPort;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostManagementAdapter implements PostManagementPort {

  private final PostRepository postRepository;

  @Override
  public Post create(Post post) {
    PostEntity postEntity = postRepository.save(PostMapper.toEntity(post));
    return PostMapper.toDomain(postEntity);
  }
}

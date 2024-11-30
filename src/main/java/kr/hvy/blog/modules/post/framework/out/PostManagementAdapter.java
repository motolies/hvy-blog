package kr.hvy.blog.modules.post.framework.out;

import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.framework.out.entity.PostEntity;
import kr.hvy.blog.modules.post.framework.out.persistence.JpaPostRepository;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostManagementAdapter implements PostManagementPort {

  private final PostMapper postMapper;
  private final JpaPostRepository jpaPostRepository;

  @Override
  public Post save(Post post) {
    PostEntity postEntity = jpaPostRepository.save(postMapper.toEntity(post));
    return postMapper.toDomain(postEntity);
  }

  @Override
  public Post findById(Long id) {
    return jpaPostRepository.findById(id)
        .map(postMapper::toDomain)
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));
  }

  @Override
  public void deleteById(Long id) {
    jpaPostRepository.deleteById(id);
  }
}

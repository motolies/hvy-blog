package kr.hvy.blog.modules.post.framework.out;

import java.util.List;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObjectDto;
import kr.hvy.blog.modules.post.framework.out.entity.PostEntity;
import kr.hvy.blog.modules.post.framework.out.persistence.JpaPostRepository;
import kr.hvy.blog.modules.post.framework.out.persistence.mapper.PostRDBMapper;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.layer.OutputAdapter;
import kr.hvy.common.mybatis.MysqlRowCountRDBMapper;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostManagementAdapter implements PostManagementPort {

  private final PostMapper postMapper;
  private final JpaPostRepository jpaPostRepository;
  private final PostRDBMapper postRDBMapper;
  private final MysqlRowCountRDBMapper countMapper;

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

  @Override
  public Post findByMain() {
    List<PostEntity> list = jpaPostRepository.findByMainPage(true);
    if (list.isEmpty()) {
      return jpaPostRepository.findByPublicAccess(true)
          .stream()
          .findFirst()
          .map(postMapper::toDomain)
          .orElseThrow(() -> new DataNotFoundException("Not Found Public Post."));
    } else {
      return postMapper.toDomain(list.getFirst());
    }
  }

  @Override
  public PostPrevNextResponse findPrevNextById(Boolean isAdmin, Long id) {
    return postRDBMapper.findPrevNextById(isAdmin, id);
  }

  @Override
  public List<Long> findByPublicPosts() {
    return postRDBMapper.findByPublicPosts();
  }

  @Override
  public List<PostNoBodyResponse> findBySearchObject(Boolean isAdmin, SearchObjectDto searchObjectDto) {
    return postRDBMapper.findBySearchObject(isAdmin, searchObjectDto);
  }

  @Override
  public Integer getTotalCount() {
    return countMapper.getTotalCount();
  }

  @Override
  public void setMainPost(Long id) {
    postRDBMapper.setMainPost(id);
  }
}

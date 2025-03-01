package kr.hvy.blog.modules.post.adapter.out;

import java.util.List;
import kr.hvy.blog.modules.category.adapter.out.entity.CategoryEntity;
import kr.hvy.blog.modules.category.adapter.out.persistence.JpaCategoryRepository;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObject;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.post.adapter.out.persistence.JpaPostRepository;
import kr.hvy.blog.modules.post.adapter.out.persistence.mapper.PostRDBMapper;
import kr.hvy.blog.modules.tag.adapter.out.entity.TagEntity;
import kr.hvy.blog.modules.tag.adapter.out.persistence.JpaTagRepository;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.layer.OutputAdapter;
import kr.hvy.common.mybatis.MysqlRowCountRDBMapper;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostManagementAdapter implements PostManagementPort {

  private final PostMapper postMapper;
  private final JpaPostRepository jpaPostRepository;
  private final JpaTagRepository jpaTagRepository;
  private final PostRDBMapper postRDBMapper;
  private final MysqlRowCountRDBMapper countMapper;
  private final JpaCategoryRepository jpaCategoryRepository;

  @Override
  public Post save(Post post) {
    CategoryEntity category = jpaCategoryRepository.findById(post.getCategoryId())
        .orElseThrow(() -> new DataNotFoundException("Not Found Category."));

    PostEntity postEntity = postMapper.toEntity(post);
    postEntity.setCategory(category);

    PostEntity savedEntity = jpaPostRepository.save(postEntity);
    return postMapper.toDomain(savedEntity);
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
  public List<PostNoBodyResponse> findBySearchObject(Boolean isAdmin, SearchObject searchObject) {
    return postRDBMapper.findBySearchObject(isAdmin, searchObject);
  }

  @Override
  public Integer getTotalCount() {
    return countMapper.getTotalCount();
  }

  @Override
  public void setMainPost(Long id) {
    postRDBMapper.setMainPost(id);
  }

  @Override
  public Post addPostTag(Long postId, Long tagId) {
    TagEntity tag = jpaTagRepository.findById(tagId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Tag."));
    PostEntity post = jpaPostRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));
    post.addTag(tag);
    return postMapper.toDomain(jpaPostRepository.save(post));
  }

  @Override
  public Post deletePostTag(Long postId, Long tagId) {
    TagEntity tag = jpaTagRepository.findById(tagId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Tag."));
    PostEntity post = jpaPostRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));
    post.removeTag(tag);
    return postMapper.toDomain(jpaPostRepository.save(post));
  }

  @Override
  public List<Post> findAll() {
    return jpaPostRepository.findAll()
        .stream()
        .map(postMapper::toDomain)
        .toList();
  }
}

package kr.hvy.blog.modules.post.application.service;

import kr.hvy.blog.modules.post.application.port.in.PostManagementUseCase;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.PostService;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostPublicRequest;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@Transactional
@RequiredArgsConstructor
public class PostManagementService implements PostManagementUseCase {

  private final PostService postService;
  private final PostMapper postMapper;
  private final PostManagementPort postManagementPort;

  @Override
  public PostResponse create(PostCreate createDto) {
    Post post = postMapper.toDomain(createDto);
    Post savedPost = postManagementPort.save(post);
    return postMapper.toResponse(savedPost);
  }

  @Override
  public PostResponse update(Long id, PostUpdate updateDto) {
    Post oldPost = postManagementPort.findById(id);
    Post newPost = postService.update(oldPost, updateDto);
    Post savedPost = postManagementPort.save(newPost);
    return postMapper.toResponse(savedPost);
  }

  @Override
  public Long delete(Long id) {
    postManagementPort.deleteById(id);
    return id;
  }

  @Override
  public void setMainPost(Long id) {
    postManagementPort.setMainPost(id);
  }

  @Override
  public PostResponse setPostVisible(PostPublicRequest postPublicRequest) {
    Post oldPost = postManagementPort.findById(postPublicRequest.getId());
    Post newPost = postService.setPostVisible(oldPost, postPublicRequest.isPublicStatus());
    Post savedPost = postManagementPort.save(newPost);
    return postMapper.toResponse(savedPost);
  }
}

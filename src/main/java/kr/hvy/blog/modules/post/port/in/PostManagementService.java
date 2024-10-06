package kr.hvy.blog.modules.post.port.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.mapper.PostMapper;
import kr.hvy.blog.modules.post.domain.model.Post;
import kr.hvy.blog.modules.post.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.usecase.PostManagementUseCase;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;

@UseCase
@RequiredArgsConstructor
public class PostManagementService implements PostManagementUseCase {

  private final PostManagementPort postManagementPort;

  @Override
  public PostResponse create(PostCreate createDto) {
    Post post = PostMapper.toDomain(createDto);
    Post savedPost = postManagementPort.create(post);
    return PostMapper.toResponse(savedPost);
  }
}

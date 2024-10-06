package kr.hvy.blog.modules.post.port.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.model.Post;
import kr.hvy.blog.modules.post.mapper.PostMapper;
import kr.hvy.blog.modules.post.usecase.PostManagementUseCase;
import org.springframework.stereotype.Service;

@Service
public class PostManagementService implements PostManagementUseCase {

  // TODO : port out 필요


  @Override
  public PostResponse create(PostCreate createDto) {

    Post post = PostMapper.toDomain(createDto);
    // TODO : output port로 저장 시도


    // TODO : 저장 후 dto 변환

    return PostMapper.toResponse(post);
  }
}

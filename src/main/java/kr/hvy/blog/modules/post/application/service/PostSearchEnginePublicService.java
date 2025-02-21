package kr.hvy.blog.modules.post.application.service;

import java.util.List;
import kr.hvy.blog.modules.post.application.port.in.PostSearchEnginePublicUseCase;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.application.port.out.PostSearchEngineManagementPort;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.PostService;
import kr.hvy.blog.modules.post.domain.dto.PostSearchEngineResponse;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;

@UseCase
@RequiredArgsConstructor
public class PostSearchEnginePublicService implements PostSearchEnginePublicUseCase {

  private final PostMapper postMapper;
  private final PostSearchEngineManagementPort postSearchEngineManagementPort;


  @Override
  public List<PostSearchEngineResponse> mainPostSearchEngines() {
    return postSearchEngineManagementPort.findAll();
  }
}

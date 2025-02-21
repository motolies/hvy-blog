package kr.hvy.blog.modules.post.adapter.out;

import java.util.List;
import kr.hvy.blog.modules.post.adapter.out.persistence.JpaSearchEngineRepository;
import kr.hvy.blog.modules.post.application.port.out.PostSearchEngineManagementPort;
import kr.hvy.blog.modules.post.domain.PostSearchEngineMapper;
import kr.hvy.blog.modules.post.domain.dto.PostSearchEngineResponse;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PostSearchEngineManagementAdapter implements PostSearchEngineManagementPort {

  private final JpaSearchEngineRepository jpaSearchEngineRepository;
  private final PostSearchEngineMapper postSearchEngineMapper;

  @Override
  public List<PostSearchEngineResponse> findAll() {
    return jpaSearchEngineRepository.findAll().stream()
        .map(postSearchEngineMapper::toResponse)
        .toList();
  }
}

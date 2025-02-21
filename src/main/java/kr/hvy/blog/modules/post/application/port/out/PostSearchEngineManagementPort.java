package kr.hvy.blog.modules.post.application.port.out;

import java.util.List;
import kr.hvy.blog.modules.post.domain.dto.PostSearchEngineResponse;

public interface PostSearchEngineManagementPort {

  List<PostSearchEngineResponse> findAll();
}

package kr.hvy.blog.modules.post.application.port.in;

import java.util.List;
import kr.hvy.blog.modules.post.domain.dto.PostSearchEngineResponse;

public interface PostSearchEnginePublicUseCase {

  /**
   * main post search 조회
   */
  List<PostSearchEngineResponse> mainPostSearchEngines();


}

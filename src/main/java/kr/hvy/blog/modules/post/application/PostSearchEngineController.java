package kr.hvy.blog.modules.post.application;

import java.util.List;
import kr.hvy.blog.modules.post.application.dto.SearchEngineResponse;
import kr.hvy.blog.modules.post.application.service.SearchEnginePublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post/search-engine")
@RequiredArgsConstructor
public class PostSearchEngineController {

  private final SearchEnginePublicService searchEnginePublicService;

  /**
   * main post의 검색엔진 표기
   */
  @GetMapping
  public List<SearchEngineResponse> getPostSearchEngine() {
    return searchEnginePublicService.findAll();
  }


}

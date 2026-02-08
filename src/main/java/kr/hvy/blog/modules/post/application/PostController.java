package kr.hvy.blog.modules.post.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import kr.hvy.blog.modules.post.application.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.application.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.application.dto.PostResponse;
import kr.hvy.blog.modules.post.application.dto.SearchObject;
import kr.hvy.blog.modules.post.application.service.PostPublicService;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostController {

  private final ObjectMapper objectMapper;
  private final PostPublicService postPublicService;

  /**
   * main post 조회
   */
  @GetMapping
  public PostResponse getMainPost() {
    return postPublicService.mainPost();
  }


  /**
   * 단일 포스트 조회
   */
  @GetMapping("/{postId}")
  public PostResponse getPost(@PathVariable int postId) {
    return postPublicService.getPost(postId);
  }


  /**
   * 이전 글/이후 글 조회
   */
  @GetMapping(value = {"/prev-next/{postId}"})
  public PostPrevNextResponse getContentPrevNext(@PathVariable int postId) {
    return postPublicService.getPrevPost(postId);
  }

  /**
   * sitemap.xml 만들기 위한 public contents id 목록을 조회
   */
  @GetMapping(value = {"/public-content"})
  public List<Long> getPublicContentId() {
    return postPublicService.getPublicPosts();
  }

  /**
   * 검색 상세(base64 인코딩한 파라미터(json object)
   */
  @GetMapping(value = {"/search"})
  public PageResponse<PostNoBodyResponse> searchDetail(@RequestParam String query) throws JsonProcessingException {
    String decodedQuery = new String(Base64.getDecoder().decode(query), StandardCharsets.UTF_8);

    // https://stackoverflow.com/questions/4486787/jackson-with-json-unrecognized-field-not-marked-as-ignorable
    SearchObject searchObject = objectMapper.readValue(decodedQuery, SearchObject.class);

    return postPublicService.searchPosts(searchObject);
  }
}

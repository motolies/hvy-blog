package kr.hvy.blog.modules.post.framework.in;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import kr.hvy.blog.modules.post.application.port.in.PostPublicUseCase;
import kr.hvy.blog.modules.post.domain.dto.SearchObjectDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private final PostPublicUseCase postPublicUseCase;

  /**
   * main post 조회
   */
  @GetMapping
  public ResponseEntity<?> getMainPost() {
    return ResponseEntity
        .status(HttpStatus.OK)
        .body(postPublicUseCase.mainPost());
  }


  /**
   * 단일 포스트 조회
   */
  @GetMapping("/{postId}")
  public ResponseEntity<?> getPost(@PathVariable int postId) {
    return ResponseEntity
        .status(HttpStatus.OK)
        .body(postPublicUseCase.getPost(postId));
  }


  /**
   * 이전 글/이후 글 조회
   */
  @GetMapping(value = {"/prev-next/{postId}"})
  public ResponseEntity<?> getContentPrevNext(@PathVariable int postId) {
    return ResponseEntity
        .status(HttpStatus.OK)
        .body(postPublicUseCase.getPrevPost(postId));
  }

  /**
   * sitemap.xml 만들기 위한 public contents id 목록을 조회
   */
  @GetMapping(value = {"/public-content"})
  public ResponseEntity<?> getPublicContentId() {
    return ResponseEntity
        .status(HttpStatus.OK)
        .body(postPublicUseCase.getPublicPosts());
  }

  /**
   * 검색 상세(base64 인코딩한 파라미터(json object)
   */
  @GetMapping(value = {"/search"})
  public ResponseEntity<?> searchDetail(@RequestParam String query) throws JsonProcessingException {
    String decodedQuery = new String(Base64.getDecoder().decode(query), StandardCharsets.UTF_8);

    // https://stackoverflow.com/questions/4486787/jackson-with-json-unrecognized-field-not-marked-as-ignorable
    SearchObjectDto searchObjectDto = objectMapper.readValue(decodedQuery, SearchObjectDto.class);

    return ResponseEntity
        .status(HttpStatus.OK)
        .body(postPublicUseCase.searchPosts(searchObjectDto));
  }
}

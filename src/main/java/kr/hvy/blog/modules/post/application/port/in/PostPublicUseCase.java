package kr.hvy.blog.modules.post.application.port.in;

import java.util.List;
import kr.hvy.blog.modules.post.domain.core.Page;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObjectDto;

public interface PostPublicUseCase {

  /**
   * main post 조회
   */
  PostResponse mainPost();

  /**
   * 단일 포스트 조회
   */
  PostResponse getPost(int id);

  /**
   * 이전 글/이후 글 조회
   */
  PostPrevNextResponse getPrevPost(int id);

  /**
   * sitemap.xml 만들기 위한 public contents id 목록을 조회
   */
  List<Long> getPublicPosts();

  /**
   * 검색 상세(base64 인코딩한 파라미터(json object)
   */
  Page<PostNoBodyResponse> searchPosts(SearchObjectDto searchObjectDto);

}

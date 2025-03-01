package kr.hvy.blog.modules.post.application.service;

import java.util.List;
import kr.hvy.blog.modules.post.application.port.in.PostPublicUseCase;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.PostService;
import kr.hvy.blog.modules.post.domain.core.Page;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObject;
import kr.hvy.common.layer.UseCase;
import kr.hvy.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;

@UseCase
@RequiredArgsConstructor
public class PostPublicService implements PostPublicUseCase {

  private final PostMapper postMapper;
  private final PostManagementPort postManagementPort;
  private final PostService postService;


  @Override
  public PostResponse mainPost() {
    return postMapper.toResponse(postManagementPort.findByMain());
  }

  @Override
  public PostResponse getPost(int id) {
    Post post = postManagementPort.findById((long) id);
    postService.checkAuthority(post);
    return postMapper.toResponse(post);
  }

  @Override
  public PostPrevNextResponse getPrevPost(int id) {
    return postManagementPort.findPrevNextById(SecurityUtils.hasAdminRole(), (long) id);
  }

  @Override
  public List<Long> getPublicPosts() {
    return postManagementPort.findByPublicPosts();
  }

  @Override
  public Page<PostNoBodyResponse> searchPosts(SearchObject searchObject) {
    // id를 먼저 가져온 후 해당 아이디로 검색

    List<PostNoBodyResponse> list = postManagementPort.findBySearchObject(SecurityUtils.hasAdminRole(), searchObject);
    int count = postManagementPort.getTotalCount();

     Page<PostNoBodyResponse> pager = new Page<>();
    pager.setList(list);
    pager.setPage(searchObject.getPage());
    pager.setPageSize(searchObject.getPageSize());
    pager.setTotalCount(count);
    return pager;
  }

}

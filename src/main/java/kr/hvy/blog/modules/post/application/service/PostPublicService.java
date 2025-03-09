package kr.hvy.blog.modules.post.application.service;

import java.util.List;
import kr.hvy.blog.modules.post.application.port.in.PostPublicUseCase;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.PostMapper;
import kr.hvy.blog.modules.post.domain.PostService;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObject;
import kr.hvy.common.domain.dto.paging.Direction;
import kr.hvy.common.domain.dto.paging.OrderBy;
import kr.hvy.common.domain.dto.paging.PageResponse;
import kr.hvy.common.layer.UseCase;
import kr.hvy.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

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
  public PageResponse<PostNoBodyResponse> searchPosts(SearchObject searchObject) {
    if (CollectionUtils.isEmpty(searchObject.getOrderBy())) {
      searchObject.getOrderBy().add(
          OrderBy.builder()
              .column("createdAt")
              .direction(Direction.DESCENDING)
              .build());
    }
    List<PostNoBodyResponse> list = postManagementPort.findBySearchObject(searchObject);
    return PageResponse.<PostNoBodyResponse>builder()
        .page(searchObject.getPage())
        .pageSize(searchObject.getPageSize())
        .totalCount(searchObject.getTotalCount())
        .list(list)
        .build();
  }

}

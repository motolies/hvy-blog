package kr.hvy.blog.modules.post.application.service;

import java.util.List;
import kr.hvy.blog.modules.post.application.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.application.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.application.dto.PostResponse;
import kr.hvy.blog.modules.post.application.dto.SearchObject;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.blog.modules.post.mapper.PostDtoMapper;
import kr.hvy.common.application.domain.dto.paging.Direction;
import kr.hvy.common.application.domain.dto.paging.OrderBy;
import kr.hvy.common.application.domain.dto.paging.PageResponse;
import kr.hvy.common.core.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PostPublicService {

  private final PostDtoMapper postDtoMapper;
  private final PostService postService;


  public PostResponse mainPost() {
    return postDtoMapper.toResponse(postService.findByMain());
  }

  public PostResponse getPost(int id) {
    Post post = postService.findByIdCheckAuthority((long) id);
    return postDtoMapper.toResponse(post);
  }

  public PostPrevNextResponse getPrevPost(int id) {
    return postService.findPrevNextById(SecurityUtils.hasAdminRole(), (long) id);
  }

  public List<Long> getPublicPosts() {
    return postService.findByPublicPosts();
  }

  public PageResponse<PostNoBodyResponse> searchPosts(SearchObject searchObject) {
    if (CollectionUtils.isEmpty(searchObject.getOrderBy())) {
      searchObject.getOrderBy().add(
          OrderBy.builder()
              .column("created_at")
              .direction(Direction.DESCENDING)
              .build());
    }
    List<PostNoBodyResponse> list = postService.findBySearchObject(searchObject);
    return PageResponse.<PostNoBodyResponse>builder()
        .page(searchObject.getPage())
        .pageSize(searchObject.getPageSize())
        .totalCount(searchObject.getTotalCount())
        .list(list)
        .build();
  }

}

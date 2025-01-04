package kr.hvy.blog.modules.post.application.port.out;

import java.util.List;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObjectDto;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;

public interface PostManagementPort {

  Post save(Post post);

  Post findById(Long id);

  void deleteById(Long id);

  Post findByMain();

  PostPrevNextResponse findPrevNextById(Boolean isAdmin, Long id);

  List<Long> findByPublicPosts();

  List<PostNoBodyResponse> findBySearchObject(Boolean isAdmin, SearchObjectDto searchObjectDto);

  Integer getTotalCount();

  void setMainPost(Long id);

  Post addPostTag(Long postId, Long tagId);

  Post deletePostTag(Long postId, Long tagId);
}

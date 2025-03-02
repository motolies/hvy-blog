package kr.hvy.blog.modules.post.application.port.out;

import java.util.List;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObject;

public interface PostManagementPort {

  Post insert(Post post);

  Post update(Post post);

  Post findById(Long id);

  void deleteById(Long id);

  Post findByMain();

  PostPrevNextResponse findPrevNextById(Boolean isAdmin, Long id);

  List<Long> findByPublicPosts();

  List<PostNoBodyResponse> findBySearchObject(Boolean isAdmin, SearchObject searchObject);

  Integer getTotalCount();

  void setMainPost(Long id);

  Post addPostTag(Long postId, Long tagId);

  Post deletePostTag(Long postId, Long tagId);

  List<Post> findAll();
}

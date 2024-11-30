package kr.hvy.blog.modules.post.application.port.out;

import kr.hvy.blog.modules.post.domain.Post;

public interface PostManagementPort {

  Post save(Post post);

  Post findById(Long id);

  void deleteById(Long id);
}

package kr.hvy.blog.modules.post.application.port.out;

import kr.hvy.blog.modules.post.domain.Post;

public interface PostManagementPort {

  Post create(Post post);

}

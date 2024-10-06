package kr.hvy.blog.modules.post.mapper;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.model.Post;

public class PostMapper {

  public static Post toDomain(PostCreate postCreate) {
    return Post.builder()
        .status(postCreate.getStatus())
        .build();
  }

  public static PostResponse toResponse(Post post) {
    return PostResponse.builder()
        .id(post.getId())
        .status(post.getStatus())
        .body(post.getBody())
        .subject(post.getSubject())
        .categoryId(post.getCategoryId())
        .isMain(post.isMain())
        .createUpdateDate(post.getCreateUpdateDate())
        .build();
  }
}

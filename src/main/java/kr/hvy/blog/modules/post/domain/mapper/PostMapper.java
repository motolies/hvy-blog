package kr.hvy.blog.modules.post.domain.mapper;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.model.Post;
import kr.hvy.common.domain.mapper.CreateUpdateDateMapper;

public class PostMapper {

  public static Post toDomain(PostCreate postCreate) {
    return Post.builder()
        .status(postCreate.getStatus())
        .subject(postCreate.getSubject())
        .body(postCreate.getBody())
        .categoryId(postCreate.getCategoryId())
        .build();
  }

  public static Post toDomain(PostEntity postEntity) {
    return Post.builder()
        .id(postEntity.getId())
        .status(postEntity.getStatus())
        .subject(postEntity.getSubject())
        .body(postEntity.getBody())
        .categoryId(postEntity.getCategoryId())
        .isPublic(postEntity.isPublic())
        .isMain(postEntity.isMain())
        .createUpdateDate(CreateUpdateDateMapper.toDomain(postEntity.getCreateUpdateDate()))
        .build();
  }

  public static PostEntity toEntity(Post post) {
    return PostEntity.builder()
        .id(post.getId())
        .status(post.getStatus())
        .subject(post.getSubject())
        .body(post.getBody())
        .categoryId(post.getCategoryId())
        .isPublic(post.isPublic())
        .isMain(post.isMain())
        .viewCount(post.getViewCount())
        .createUpdateDate(CreateUpdateDateMapper.toEntity(post.getCreateUpdateDate()))
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

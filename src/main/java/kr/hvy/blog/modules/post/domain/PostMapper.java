package kr.hvy.blog.modules.post.domain;

import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PostMapper {

  PostMapper INSTANCE = Mappers.getMapper(PostMapper.class);

  PostEntity toEntity(Post post);

  PostResponse toResponse(Post post);

  Post toDomain(PostCreate postCreate);

  Post toDomain(PostEntity postEntity);



  @ObjectFactory
  default Post.PostBuilder createPostBuilder() {
    return Post.builder();
  }
}

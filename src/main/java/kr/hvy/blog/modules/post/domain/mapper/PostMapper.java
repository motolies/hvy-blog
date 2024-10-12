package kr.hvy.blog.modules.post.domain.mapper;

import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.model.Post;
import org.mapstruct.Mapper;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PostMapper {

  PostMapper INSTANCE = Mappers.getMapper(PostMapper.class);

  // 매핑 메서드 정의
  Post toDomain(PostCreate postCreate);

  Post toDomain(PostEntity postEntity);

  PostEntity toEntity(Post post);

  PostResponse toResponse(Post post);

  @ObjectFactory
  default Post.PostBuilder createPostBuilder() {
    return Post.builder();
  }
}

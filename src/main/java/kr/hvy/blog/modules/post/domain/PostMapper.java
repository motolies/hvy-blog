package kr.hvy.blog.modules.post.domain;

import kr.hvy.blog.modules.file.domain.FileMapper;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.tag.domain.TagMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring",
    // 사용하는 하위 맵퍼 클래스 명시
    uses = {TagMapper.class, FileMapper.class}
)

public interface PostMapper {

  PostMapper INSTANCE = Mappers.getMapper(PostMapper.class);

  PostEntity toEntity(Post post);

  PostResponse toResponse(Post post);

  Post toDomain(PostCreate postCreate);

  // 맵핑에서 제외할 속성들
  @Mapping(target = "tags.posts", ignore = true)
  @Mapping(target = "files.post", ignore = true)
  @Mapping(source = "category.id", target = "categoryId")
  Post toDomain(PostEntity postEntity);

  @ObjectFactory
  default Post.PostBuilder createPostBuilder() {
    return Post.builder();
  }
}

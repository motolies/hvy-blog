package kr.hvy.blog.modules.post.domain;

import kr.hvy.blog.modules.file.domain.FileMapper;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
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

  @Mapping(source = "public", target = "publicAccess")
  @Mapping(source = "main", target = "mainPage")
  PostEntity toEntity(Post post);

  @Mapping(source = "category.id", target = "categoryId")
  PostResponse toResponse(Post post);

  Post toDomain(PostCreate postCreate);

  // 맵핑에서 제외할 속성들
  @Mapping(target = "tags.posts", ignore = true)
  @Mapping(target = "files.post", ignore = true)
  @Mapping(source = "category.id", target = "categoryId")
  @Mapping(source = "publicAccess", target = "isPublic")
  @Mapping(source = "mainPage", target = "isMain")
  Post toDomain(PostEntity postEntity);

  @ObjectFactory
  default Post.PostBuilder createPostBuilder() {
    return Post.builder();
  }
}

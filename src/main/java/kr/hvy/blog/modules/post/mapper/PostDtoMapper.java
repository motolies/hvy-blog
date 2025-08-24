package kr.hvy.blog.modules.post.mapper;

import kr.hvy.blog.modules.category.mapper.CategoryDtoMapper;
import kr.hvy.blog.modules.file.mapper.FileDtoMapper;
import kr.hvy.blog.modules.post.application.dto.PostCreate;
import kr.hvy.blog.modules.post.application.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.blog.modules.tag.mapper.TagDtoMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring",
    // 사용하는 하위 맵퍼 클래스 명시
    uses = {TagDtoMapper.class, FileDtoMapper.class, CategoryDtoMapper.class}
)

public interface PostDtoMapper {

  @Mapping(source = "category.id", target = "categoryId")
  @Mapping(source = "publicAccess", target = "isPublic")
  @Mapping(source = "mainPage", target = "isMain")
  PostResponse toResponse(Post postEntity);

  Post toDomain(PostCreate postCreate);
}

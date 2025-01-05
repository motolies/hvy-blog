package kr.hvy.blog.modules.tag.domain;

import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;
import kr.hvy.blog.modules.tag.framework.out.entity.TagEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface TagMapper {

  TagMapper INSTANCE = Mappers.getMapper(TagMapper.class);

  TagEntity toEntity(Tag tag);

  TagResponse toResponse(Tag tag);

  Tag toDomain(TagCreate tagCreate);

  // 맵핑에서 제외할 속성들
  @Mapping(target = "posts.tags", ignore = true)
  @Mapping(target = "posts.files", ignore = true)
  Tag toDomain(TagEntity tagEntity);

  @ObjectFactory
  default Tag.TagBuilder createTagBuilder() {
    return Tag.builder();
  }

}

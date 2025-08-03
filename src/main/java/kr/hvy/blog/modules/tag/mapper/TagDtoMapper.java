package kr.hvy.blog.modules.tag.mapper;

import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;

import kr.hvy.blog.modules.tag.domain.Tag;
import org.mapstruct.Mapper;

import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface TagDtoMapper {

  TagDtoMapper INSTANCE = Mappers.getMapper(TagDtoMapper.class);

  TagResponse toResponse(Tag tag);

  Tag toDomain(TagCreate tagCreate);

}

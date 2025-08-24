package kr.hvy.blog.modules.tag.mapper;

import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.domain.Tag;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TagDtoMapper {

  TagResponse toResponse(Tag tag);

  Tag toDomain(TagCreate tagCreate);

}

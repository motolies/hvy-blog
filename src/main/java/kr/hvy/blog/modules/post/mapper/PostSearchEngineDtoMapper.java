package kr.hvy.blog.modules.post.mapper;

import kr.hvy.blog.modules.post.domain.entity.PostSearchEngineEntity;
import kr.hvy.blog.modules.post.application.dto.PostSearchEngineResponse;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PostSearchEngineDtoMapper {

  PostSearchEngineDtoMapper INSTANCE = Mappers.getMapper(PostSearchEngineDtoMapper.class);

  PostSearchEngineResponse toResponse(PostSearchEngineEntity postSearchEngineEntity);

}

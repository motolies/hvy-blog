package kr.hvy.blog.modules.post.mapper;

import kr.hvy.blog.modules.post.application.dto.PostSearchEngineResponse;
import kr.hvy.blog.modules.post.domain.entity.PostSearchEngineEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PostSearchEngineDtoMapper {

  PostSearchEngineResponse toResponse(PostSearchEngineEntity postSearchEngineEntity);

}

package kr.hvy.blog.modules.post.domain;

import kr.hvy.blog.modules.post.adapter.out.entity.PostSearchEngineEntity;
import kr.hvy.blog.modules.post.domain.dto.PostSearchEngineResponse;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PostSearchEngineMapper {

  PostSearchEngineMapper INSTANCE = Mappers.getMapper(PostSearchEngineMapper.class);

  PostSearchEngineResponse toResponse(PostSearchEngineEntity postSearchEngineEntity);

}

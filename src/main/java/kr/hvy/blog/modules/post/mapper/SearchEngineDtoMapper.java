package kr.hvy.blog.modules.post.mapper;

import kr.hvy.blog.modules.post.application.dto.SearchEngineResponse;
import kr.hvy.blog.modules.post.domain.entity.SearchEngine;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SearchEngineDtoMapper {

  SearchEngineResponse toResponse(SearchEngine searchEngine);

}

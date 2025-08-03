package kr.hvy.blog.modules.category.mapper;


import kr.hvy.blog.modules.category.domain.entity.Category;
import kr.hvy.blog.modules.category.application.dto.CategoryCreate;
import kr.hvy.blog.modules.category.application.dto.CategoryResponse;
import kr.hvy.blog.modules.category.application.dto.CategorySingleResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface CategoryDtoMapper {

  CategoryDtoMapper INSTANCE = Mappers.getMapper(CategoryDtoMapper.class);

  @Mapping(source = "seq", target = "order")
  CategoryResponse toResponse(Category category);

  @Mapping(source = "seq", target = "order")
  CategorySingleResponse toResponseSingle(Category category);

  Category toDomain(CategoryCreate categoryCreate);

}

package kr.hvy.blog.modules.category.domain;

import kr.hvy.blog.modules.category.adapter.out.entity.CategoryEntity;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;
import kr.hvy.blog.modules.category.domain.dto.CategorySingleResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

  CategoryMapper INSTANCE = Mappers.getMapper(CategoryMapper.class);

  CategoryEntity toEntity(Category category);

  Category toDomain(CategoryEntity categoryEntity);

  @Mapping(source = "seq", target = "order")
  CategoryResponse toResponse(CategoryEntity categoryEntity);

  @Mapping(source = "seq", target = "order")
  CategorySingleResponse toResponseSingle(CategoryEntity categoryEntity);

  @Mapping(source = "seq", target = "order")
  CategoryResponse toResponse(Category category);

  Category toDomain(CategoryCreate categoryCreate);


  @ObjectFactory
  default Category.CategoryBuilder createTagBuilder() {
    return Category.builder();
  }

}

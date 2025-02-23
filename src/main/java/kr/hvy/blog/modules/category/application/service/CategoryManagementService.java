package kr.hvy.blog.modules.category.application.service;

import kr.hvy.blog.modules.category.application.port.in.CategoryManagementUseCase;
import kr.hvy.blog.modules.category.application.port.out.CategoryManagementPort;
import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.category.domain.CategoryMapper;
import kr.hvy.blog.modules.category.domain.CategoryService;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@Transactional
@RequiredArgsConstructor
public class CategoryManagementService implements CategoryManagementUseCase {

  private final CategoryMapper categoryMapper;
  private final CategoryService categoryService;
  private final CategoryManagementPort categoryManagementPort;

  @Override
  public CategoryResponse create(CategoryCreate createDto) {
    Category newCategory = categoryService.create(createDto);
    Category savedCategory = categoryManagementPort.save(newCategory);
    return categoryMapper.toResponse(savedCategory);
  }

  @Override
  public CategoryResponse update(String s, Void updateDto) {
    // todo : 나중에 구현할지 말지 정해야 함
    return CategoryManagementUseCase.super.update(s, updateDto);
  }

  @Override
  public String delete(String categoryId) {
    Category category = categoryManagementPort.findById(categoryId);
    categoryService.delete(category);
    return categoryManagementPort.deleteById(categoryId);
  }


}

package kr.hvy.blog.modules.category.application.service;

import kr.hvy.blog.modules.category.application.port.in.CategoryManagementUseCase;
import kr.hvy.blog.modules.category.application.port.out.CategoryManagementPort;
import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.category.domain.CategoryMapper;
import kr.hvy.blog.modules.category.domain.CategoryService;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;
import kr.hvy.blog.modules.category.domain.dto.CategoryUpdate;
import kr.hvy.common.domain.dto.DeleteResponse;
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
  public CategoryResponse update(String categoryId, CategoryUpdate updateDto) {
    Category category = categoryManagementPort.findById(categoryId);
    Category parentCategory = categoryManagementPort.findById(updateDto.getParentId());
    Category updatedCategory = categoryService.update(category, updateDto);

    Category savedCategory = categoryManagementPort.save(updatedCategory);
    return categoryMapper.toResponse(savedCategory);
  }

  @Override
  public DeleteResponse<String> delete(String categoryId) {
    Category category = categoryManagementPort.findById(categoryId);
    categoryService.delete(category);
    categoryManagementPort.deleteById(categoryId);
    return DeleteResponse.<String>builder()
        .id(categoryId).build();
  }


}

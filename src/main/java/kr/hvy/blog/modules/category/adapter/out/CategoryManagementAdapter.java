package kr.hvy.blog.modules.category.adapter.out;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import kr.hvy.blog.modules.category.adapter.out.entity.CategoryEntity;
import kr.hvy.blog.modules.category.adapter.out.persistence.JpaCategoryRepository;
import kr.hvy.blog.modules.category.adapter.out.persistence.mapper.CategoryRDBMapper;
import kr.hvy.blog.modules.category.application.port.out.CategoryManagementPort;
import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.category.domain.CategoryConstant;
import kr.hvy.blog.modules.category.domain.CategoryMapper;
import kr.hvy.blog.modules.category.domain.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class CategoryManagementAdapter implements CategoryManagementPort {

  @PersistenceContext
  private EntityManager entityManager; // EntityManager 주입

  private final CategoryMapper categoryMapper;
  private final JpaCategoryRepository jpaCategoryRepository;
  private final CategoryRDBMapper categoryRDBMapper;


  @Override
  public CategoryResponse findByRoot() {
    return categoryMapper.toResponse(jpaCategoryRepository.findById(CategoryConstant.ROOT_CATEGORY_ID)
        .orElseThrow(() -> new IllegalArgumentException("카테고리가 존재하지 않습니다.")));
  }

  @Override
  public Category save(Category category) {
    CategoryEntity categoryEntity = jpaCategoryRepository.save(categoryMapper.toEntity(category));
    entityManager.flush();
    entityManager.refresh(categoryEntity);
    return categoryMapper.toDomain(categoryEntity);
  }

  @Override
  public String deleteById(String id) {
    jpaCategoryRepository.deleteById(id);
    return id;
  }

  @Override
  public List<CategoryFlatResponse> findAllCategory() {
    return categoryRDBMapper.findAllCategory();
  }

  @Override
  public Category findById(String id) {
    return jpaCategoryRepository.findById(id)
        .map(categoryMapper::toDomain)
        .orElseThrow(() -> new IllegalArgumentException("카테고리가 존재하지 않습니다."));
  }
}

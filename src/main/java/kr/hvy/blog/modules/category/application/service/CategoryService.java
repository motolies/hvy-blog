package kr.hvy.blog.modules.category.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import kr.hvy.blog.modules.category.application.dto.CategoryCreate;
import kr.hvy.blog.modules.category.application.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryUpdate;
import kr.hvy.blog.modules.category.application.specification.CategoryCreateSpecification;
import kr.hvy.blog.modules.category.application.specification.CategoryDeleteSpecification;
import kr.hvy.blog.modules.category.application.specification.CategoryUpdateSpecification;
import kr.hvy.blog.modules.category.domain.code.CategoryConstant;
import kr.hvy.blog.modules.category.domain.entity.Category;
import kr.hvy.blog.modules.category.mapper.CategoryDtoMapper;
import kr.hvy.blog.modules.category.repository.CategoryRepository;
import kr.hvy.blog.modules.category.repository.mapper.CategoryMapper;
import kr.hvy.blog.modules.common.cache.domain.code.CacheConstant;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.specification.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CategoryService {

  @PersistenceContext
  private EntityManager entityManager; // EntityManager 주입

  private final CategoryDtoMapper categoryDtoMapper;
  private final CategoryRepository categoryRepository;
  private final CategoryMapper categoryMapper;

  public CategoryResponse findByRoot() {
    return categoryDtoMapper.toResponse(categoryRepository.findById(CategoryConstant.ROOT_CATEGORY_ID)
        .orElseThrow(() -> new DataNotFoundException("카테고리가 존재하지 않습니다.")));
  }

  public Category save(Category category) {
    Category categoryEntity = categoryRepository.save(category);
    entityManager.flush();
    entityManager.refresh(categoryEntity);
    return categoryEntity;
  }

  public List<CategoryFlatResponse> findAllCategory() {
    return categoryMapper.findAllCategory();
  }

  public Category findById(String id) {
    return categoryRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("카테고리가 존재하지 않습니다."));
  }

  @Caching(evict = {
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ALL),
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ROOT)
  })
  public CategoryResponse create(CategoryCreate createDto) {
    Specification.validate(CategoryCreateSpecification::new, createDto);
    Category savedCategory = save(categoryDtoMapper.toDomain(createDto));
    return categoryDtoMapper.toResponse(savedCategory);
  }


  @CachePut(cacheNames = CacheConstant.CATEGORY, key = "#categoryId")
  @Caching(evict = {
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ALL),
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ROOT)
  })
  public CategoryResponse update(String categoryId, CategoryUpdate updateDto) {
    if (CategoryConstant.ROOT_CATEGORY_ID.equals(categoryId)) {
      throw new IllegalArgumentException("ROOT 카테고리는 변경할 수 없습니다.");
    }

    Specification.validate(CategoryUpdateSpecification::new, updateDto);

    Category category = findById(categoryId);
    category.update(updateDto.getName(), updateDto.getParentId());

    Category savedCategory = save(category);
    return categoryDtoMapper.toResponse(savedCategory);
  }

  @Caching(evict = {
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ALL),
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ROOT),
      @CacheEvict(cacheNames = CacheConstant.CATEGORY, key = "#categoryId")
  })
  public DeleteResponse<String> delete(String categoryId) {
    Category category = findById(categoryId);
    Specification.validate(CategoryDeleteSpecification::new, category);
    categoryRepository.deleteById(categoryId);

    return DeleteResponse.<String>builder()
        .id(categoryId).build();
  }
}

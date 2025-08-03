package kr.hvy.blog.modules.category.application.service;

import java.util.List;
import kr.hvy.blog.modules.category.application.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryResponse;
import kr.hvy.blog.modules.common.cache.domain.code.CacheConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CategoryPublicService {

  private final CategoryService categoryService;

  @Cacheable(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ALL)
  public List<CategoryFlatResponse> getAllCategories() {
    return categoryService.findAllCategory();
  }

  @Cacheable(cacheNames = CacheConstant.CATEGORY, key = CacheConstant.ROOT)
  public CategoryResponse getRootCategory() {
    return categoryService.findByRoot();
  }
}

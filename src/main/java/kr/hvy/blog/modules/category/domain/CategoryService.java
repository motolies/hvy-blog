package kr.hvy.blog.modules.category.domain;

import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.specification.CategoryCreateSpecification;
import kr.hvy.blog.modules.category.domain.specification.CategoryDeleteSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CategoryService {

  private final CategoryMapper categoryMapper;
  private final CategoryCreateSpecification categoryCreateSpecification = new CategoryCreateSpecification();
  private final CategoryDeleteSpecification categoryDeleteSpecification = new CategoryDeleteSpecification();

  public Category create(CategoryCreate createDto) {
    // todo : 나중에 상위 카테고리도 가져와서 있는지 확인해야함
    categoryCreateSpecification.validateException(createDto);
    return categoryMapper.toDomain(createDto);
  }

  public void delete(String categoryId) {
    categoryDeleteSpecification.validateException(categoryId);
  }

}

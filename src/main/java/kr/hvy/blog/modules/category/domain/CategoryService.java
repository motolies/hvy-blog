package kr.hvy.blog.modules.category.domain;


import io.hypersistence.tsid.TSID;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.dto.CategoryUpdate;
import kr.hvy.blog.modules.category.domain.specification.CategoryCreateSpecification;
import kr.hvy.blog.modules.category.domain.specification.CategoryDeleteSpecification;
import kr.hvy.blog.modules.category.domain.specification.CategoryUpdateSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CategoryService {

  private final CategoryMapper categoryMapper;
  private final CategoryCreateSpecification categoryCreateSpecification = new CategoryCreateSpecification();
  private final CategoryDeleteSpecification categoryDeleteSpecification = new CategoryDeleteSpecification();
  private final CategoryUpdateSpecification categoryUpdateSpecification = new CategoryUpdateSpecification();

  public Category create(CategoryCreate createDto) {
    // todo : 나중에 상위 카테고리도 가져와서 있는지 확인해야함
    categoryCreateSpecification.validateException(createDto);
    // 새로운 아이디 생성
    createDto.setId(TSID.fast().toString());
    return categoryMapper.toDomain(createDto);
  }

  public void delete(Category categoryId) {
    categoryDeleteSpecification.validateException(categoryId);
  }

  public Category update(Category category, CategoryUpdate updateDto) {
    categoryUpdateSpecification.validateException(updateDto);
    return category
        .withName(updateDto.getName())
        .withParentId(updateDto.getParentId());
  }
}

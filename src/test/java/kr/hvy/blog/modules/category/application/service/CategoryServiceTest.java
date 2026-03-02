package kr.hvy.blog.modules.category.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.category.application.dto.CategoryCreate;
import kr.hvy.blog.modules.category.application.dto.CategoryFlatResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryResponse;
import kr.hvy.blog.modules.category.application.dto.CategoryUpdate;
import kr.hvy.blog.modules.category.domain.code.CategoryConstant;
import kr.hvy.blog.modules.category.domain.entity.Category;
import kr.hvy.blog.modules.category.mapper.CategoryDtoMapper;
import kr.hvy.blog.modules.category.repository.CategoryRepository;
import kr.hvy.blog.modules.category.repository.mapper.CategoryMapper;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

  @Mock
  EntityManager entityManager;

  @Mock
  CategoryDtoMapper categoryDtoMapper;

  @Mock
  CategoryRepository categoryRepository;

  @Mock
  CategoryMapper categoryMapper;

  @InjectMocks
  CategoryService categoryService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(categoryService, "entityManager", entityManager);
  }

  private Category createCategory(String id, String name) {
    return Category.builder()
        .id(id)
        .name(name)
        .seq(1)
        .fullName(name)
        .fullPath("/" + name + "/")
        .categories(new ArrayList<>())
        .build();
  }

  private CategoryResponse createCategoryResponse(String id, String name) {
    return CategoryResponse.builder()
        .id(id)
        .name(name)
        .categories(List.of())
        .build();
  }

  @Nested
  @DisplayName("findByRoot")
  class FindByRoot {

    @Test
    @DisplayName("루트 카테고리가 존재하면 CategoryResponse를 반환한다")
    void findByRoot_rootExists_returnsCategoryResponse() {
      // Given
      Category rootCategory = createCategory(CategoryConstant.ROOT_CATEGORY_ID, "ROOT");
      CategoryResponse response = createCategoryResponse(CategoryConstant.ROOT_CATEGORY_ID, "ROOT");
      given(categoryRepository.findById(CategoryConstant.ROOT_CATEGORY_ID)).willReturn(Optional.of(rootCategory));
      given(categoryDtoMapper.toResponse(rootCategory)).willReturn(response);

      // When
      CategoryResponse result = categoryService.findByRoot();

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("루트 카테고리가 없으면 DataNotFoundException이 발생한다")
    void findByRoot_rootNotExists_throwsDataNotFoundException() {
      // Given
      given(categoryRepository.findById(CategoryConstant.ROOT_CATEGORY_ID)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> categoryService.findByRoot())
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("findAllCategory")
  class FindAllCategory {

    @Test
    @DisplayName("카테고리가 존재하면 플랫 목록을 반환한다")
    void findAllCategory_categoriesExist_returnsList() {
      // Given
      List<CategoryFlatResponse> flatList = List.of(
          CategoryFlatResponse.builder().id("1").name("Category1").build(),
          CategoryFlatResponse.builder().id("2").name("Category2").build()
      );
      given(categoryMapper.findAllCategory()).willReturn(flatList);

      // When
      List<CategoryFlatResponse> result = categoryService.findAllCategory();

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("카테고리가 없으면 빈 목록을 반환한다")
    void findAllCategory_noCategories_returnsEmptyList() {
      // Given
      given(categoryMapper.findAllCategory()).willReturn(List.of());

      // When
      List<CategoryFlatResponse> result = categoryService.findAllCategory();

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findById")
  class FindById {

    @Test
    @DisplayName("존재하는 ID로 조회하면 카테고리를 반환한다")
    void findById_existingId_returnsCategory() {
      // Given
      Category category = createCategory("cat1", "Java");
      given(categoryRepository.findById("cat1")).willReturn(Optional.of(category));

      // When
      Category result = categoryService.findById("cat1");

      // Then
      assertThat(result).isEqualTo(category);
    }

    @Test
    @DisplayName("존재하지 않는 ID로 조회하면 DataNotFoundException이 발생한다")
    void findById_nonExistingId_throwsDataNotFoundException() {
      // Given
      given(categoryRepository.findById("nonExistent")).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> categoryService.findById("nonExistent"))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("정상 요청이면 CategoryResponse를 반환한다")
    void create_validRequest_returnsCategoryResponse() {
      // Given
      CategoryCreate createDto = CategoryCreate.builder()
          .name("Java")
          .parentId(CategoryConstant.ROOT_CATEGORY_ID)
          .build();
      Category category = createCategory("newCat", "Java");
      CategoryResponse response = createCategoryResponse("newCat", "Java");
      given(categoryDtoMapper.toDomain(createDto)).willReturn(category);
      given(categoryRepository.save(any())).willReturn(category);
      given(categoryDtoMapper.toResponse(category)).willReturn(response);

      // When
      CategoryResponse result = categoryService.create(createDto);

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("이름이 빈 값이면 SpecificationException이 발생한다")
    void create_emptyName_throwsSpecificationException() {
      // Given
      CategoryCreate createDto = CategoryCreate.builder()
          .name("")
          .parentId(CategoryConstant.ROOT_CATEGORY_ID)
          .build();

      // When & Then
      assertThatThrownBy(() -> categoryService.create(createDto))
          .isInstanceOf(SpecificationException.class);
    }

    @Test
    @DisplayName("부모 ID가 빈 값이면 SpecificationException이 발생한다")
    void create_emptyParentId_throwsSpecificationException() {
      // Given
      CategoryCreate createDto = CategoryCreate.builder()
          .name("Java")
          .parentId("")
          .build();

      // When & Then
      assertThatThrownBy(() -> categoryService.create(createDto))
          .isInstanceOf(SpecificationException.class);
    }
  }

  @Nested
  @DisplayName("update")
  class Update {

    @Test
    @DisplayName("정상 요청이면 업데이트된 CategoryResponse를 반환한다")
    void update_validRequest_returnsCategoryResponse() {
      // Given
      String categoryId = "cat1";
      CategoryUpdate updateDto = CategoryUpdate.builder()
          .name("Spring")
          .parentId(CategoryConstant.ROOT_CATEGORY_ID)
          .build();
      Category category = createCategory(categoryId, "Java");
      CategoryResponse response = createCategoryResponse(categoryId, "Spring");
      given(categoryRepository.findById(categoryId)).willReturn(Optional.of(category));
      given(categoryRepository.save(any())).willReturn(category);
      given(categoryDtoMapper.toResponse(category)).willReturn(response);

      // When
      CategoryResponse result = categoryService.update(categoryId, updateDto);

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("ROOT 카테고리를 업데이트하면 IllegalArgumentException이 발생한다")
    void update_rootCategory_throwsIllegalArgumentException() {
      // Given
      CategoryUpdate updateDto = CategoryUpdate.builder()
          .name("변경시도")
          .parentId("someParent")
          .build();

      // When & Then
      assertThatThrownBy(() -> categoryService.update(CategoryConstant.ROOT_CATEGORY_ID, updateDto))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름이 빈 값이면 SpecificationException이 발생한다")
    void update_emptyName_throwsSpecificationException() {
      // Given
      CategoryUpdate updateDto = CategoryUpdate.builder()
          .name("")
          .parentId(CategoryConstant.ROOT_CATEGORY_ID)
          .build();

      // When & Then
      assertThatThrownBy(() -> categoryService.update("cat1", updateDto))
          .isInstanceOf(SpecificationException.class);
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {

    @Test
    @DisplayName("정상 요청이면 DeleteResponse를 반환한다")
    void delete_validCategory_returnsDeleteResponse() {
      // Given
      String categoryId = "cat1";
      Category category = createCategory(categoryId, "Java");
      given(categoryRepository.findById(categoryId)).willReturn(Optional.of(category));

      // When
      DeleteResponse<String> result = categoryService.delete(categoryId);

      // Then
      assertThat(result.getId()).isEqualTo(categoryId);
      then(categoryRepository).should().deleteById(categoryId);
    }

    @Test
    @DisplayName("ROOT 카테고리를 삭제하면 SpecificationException이 발생한다")
    void delete_rootCategory_throwsSpecificationException() {
      // Given
      Category rootCategory = createCategory(CategoryConstant.ROOT_CATEGORY_ID, "ROOT");
      given(categoryRepository.findById(CategoryConstant.ROOT_CATEGORY_ID)).willReturn(Optional.of(rootCategory));

      // When & Then
      assertThatThrownBy(() -> categoryService.delete(CategoryConstant.ROOT_CATEGORY_ID))
          .isInstanceOf(SpecificationException.class);
    }

    @Test
    @DisplayName("하위 카테고리가 존재하면 SpecificationException이 발생한다")
    void delete_categoryWithChildren_throwsSpecificationException() {
      // Given
      String categoryId = "cat1";
      Category childCategory = createCategory("child1", "Child");
      Category category = Category.builder()
          .id(categoryId)
          .name("Parent")
          .seq(1)
          .fullName("Parent")
          .fullPath("/Parent/")
          .categories(List.of(childCategory))
          .build();
      given(categoryRepository.findById(categoryId)).willReturn(Optional.of(category));

      // When & Then
      assertThatThrownBy(() -> categoryService.delete(categoryId))
          .isInstanceOf(SpecificationException.class);
    }
  }
}

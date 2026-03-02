package kr.hvy.blog.modules.tag.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.mapper.TagDtoMapper;
import kr.hvy.blog.modules.tag.repository.TagRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

  @Mock
  TagDtoMapper tagDtoMapper;

  @Mock
  TagRepository tagRepository;

  @InjectMocks
  TagService tagService;

  @Nested
  @DisplayName("findById")
  class FindById {

    @Test
    @DisplayName("존재하는 ID로 조회하면 태그를 반환한다")
    void findById_existingId_returnsTag() {
      // Given
      Tag tag = Tag.builder().id(1L).name("Java").build();
      given(tagRepository.findById(1L)).willReturn(Optional.of(tag));

      // When
      Tag result = tagService.findById(1L);

      // Then
      assertThat(result).isEqualTo(tag);
      then(tagRepository).should().findById(1L);
    }

    @Test
    @DisplayName("존재하지 않는 ID로 조회하면 DataNotFoundException이 발생한다")
    void findById_nonExistingId_throwsDataNotFoundException() {
      // Given
      given(tagRepository.findById(999L)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> tagService.findById(999L))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("getAllTags")
  class GetAllTags {

    @Test
    @DisplayName("태그가 존재하면 목록을 반환한다")
    void getAllTags_tagsExist_returnsList() {
      // Given
      Tag tag1 = Tag.builder().id(1L).name("Java").build();
      Tag tag2 = Tag.builder().id(2L).name("Spring").build();
      TagResponse response1 = TagResponse.builder().id(1L).name("Java").postCount(0).build();
      TagResponse response2 = TagResponse.builder().id(2L).name("Spring").postCount(0).build();
      given(tagRepository.findAll()).willReturn(List.of(tag1, tag2));
      given(tagDtoMapper.toResponse(tag1)).willReturn(response1);
      given(tagDtoMapper.toResponse(tag2)).willReturn(response2);

      // When
      List<TagResponse> result = tagService.getAllTags();

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("태그가 없으면 빈 목록을 반환한다")
    void getAllTags_noTags_returnsEmptyList() {
      // Given
      given(tagRepository.findAll()).willReturn(List.of());

      // When
      List<TagResponse> result = tagService.getAllTags();

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByNameContainingOrderByName")
  class FindByNameContaining {

    @Test
    @DisplayName("이름이 있으면 이름으로 검색한 결과를 반환한다")
    void findByNameContainingOrderByName_withName_returnsSearchedList() {
      // Given
      Tag tag = Tag.builder().id(1L).name("Java").build();
      TagResponse response = TagResponse.builder().id(1L).name("Java").postCount(0).build();
      given(tagRepository.findByNameContainingOrderByName("Java")).willReturn(Set.of(tag));
      given(tagDtoMapper.toResponse(tag)).willReturn(response);

      // When
      List<TagResponse> result = tagService.findByNameContainingOrderByName("Java");

      // Then
      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("이름이 빈 값이면 전체 태그를 반환한다")
    void findByNameContainingOrderByName_emptyName_returnsAllTags() {
      // Given
      Tag tag = Tag.builder().id(1L).name("Java").build();
      TagResponse response = TagResponse.builder().id(1L).name("Java").postCount(0).build();
      given(tagRepository.findAllByOrderByName()).willReturn(Set.of(tag));
      given(tagDtoMapper.toResponse(tag)).willReturn(response);

      // When
      List<TagResponse> result = tagService.findByNameContainingOrderByName("");

      // Then
      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("이름이 null이면 전체 태그를 반환한다")
    void findByNameContainingOrderByName_nullName_returnsAllTags() {
      // Given
      given(tagRepository.findAllByOrderByName()).willReturn(Set.of());

      // When
      List<TagResponse> result = tagService.findByNameContainingOrderByName(null);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("createIfNotExists")
  class CreateIfNotExists {

    @Test
    @DisplayName("이미 존재하는 태그면 기존 태그를 반환한다")
    void createIfNotExists_tagExists_returnsExistingTag() {
      // Given
      TagCreate createDto = TagCreate.builder().name("Java").build();
      Tag existingTag = Tag.builder().id(1L).name("Java").build();
      given(tagRepository.findByName("Java")).willReturn(Optional.of(existingTag));

      // When
      Tag result = tagService.createIfNotExists(createDto);

      // Then
      assertThat(result).isEqualTo(existingTag);
    }

    @Test
    @DisplayName("존재하지 않는 태그면 새로 생성하여 반환한다")
    void createIfNotExists_tagNotExists_createsNewTag() {
      // Given
      TagCreate createDto = TagCreate.builder().name("Kotlin").build();
      Tag newTag = Tag.builder().id(2L).name("Kotlin").build();
      given(tagRepository.findByName("Kotlin")).willReturn(Optional.empty());
      given(tagDtoMapper.toDomain(createDto)).willReturn(newTag);
      given(tagRepository.save(newTag)).willReturn(newTag);

      // When
      Tag result = tagService.createIfNotExists(createDto);

      // Then
      assertThat(result).isEqualTo(newTag);
    }

    @Test
    @DisplayName("빈 이름으로 생성하면 SpecificationException이 발생한다")
    void createIfNotExists_emptyName_throwsSpecificationException() {
      // Given
      TagCreate createDto = TagCreate.builder().name("").build();

      // When & Then
      assertThatThrownBy(() -> tagService.createIfNotExists(createDto))
          .isInstanceOf(SpecificationException.class);
    }
  }

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("정상 요청이면 TagResponse를 반환한다")
    void create_validRequest_returnsTagResponse() {
      // Given
      TagCreate createDto = TagCreate.builder().name("Java").build();
      Tag tag = Tag.builder().id(1L).name("Java").build();
      TagResponse response = TagResponse.builder().id(1L).name("Java").postCount(0).build();
      given(tagRepository.findByName("Java")).willReturn(Optional.of(tag));
      given(tagDtoMapper.toResponse(tag)).willReturn(response);

      // When
      TagResponse result = tagService.create(createDto);

      // Then
      assertThat(result).isEqualTo(response);
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {

    @Test
    @DisplayName("정상 요청이면 DeleteResponse를 반환하고 deleteById가 호출된다")
    void delete_validId_returnsDeleteResponseAndCallsDeleteById() {
      // Given
      Long id = 1L;

      // When
      DeleteResponse<Long> result = tagService.delete(id);

      // Then
      assertThat(result.getId()).isEqualTo(id);
      then(tagRepository).should().deleteById(id);
    }
  }
}

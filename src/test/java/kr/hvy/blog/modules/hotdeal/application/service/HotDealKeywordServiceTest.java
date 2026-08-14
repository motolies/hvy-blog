package kr.hvy.blog.modules.hotdeal.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordCreate;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordResponse;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordUpdate;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import kr.hvy.blog.modules.hotdeal.repository.HotDealKeywordRepository;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HotDealKeywordServiceTest {

  @Mock
  HotDealKeywordRepository keywordRepository;

  @InjectMocks
  HotDealKeywordService hotDealKeywordService;

  @Captor
  ArgumentCaptor<HotDealKeyword> keywordCaptor;

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("정상 등록하면 정규화 값이 함께 저장된다")
    void create_validKeyword_savesNormalizedValue() {
      // Given
      HotDealKeywordCreate request = HotDealKeywordCreate.builder()
          .keyword("그래픽 카드")
          .enabled(true)
          .build();
      given(keywordRepository.existsByNormalizedKeyword("그래픽카드")).willReturn(false);
      given(keywordRepository.save(any(HotDealKeyword.class)))
          .willAnswer(invocation -> invocation.getArgument(0));

      // When
      HotDealKeywordResponse response = hotDealKeywordService.create(request);

      // Then
      then(keywordRepository).should().save(keywordCaptor.capture());
      assertThat(keywordCaptor.getValue().getKeyword()).isEqualTo("그래픽 카드");
      assertThat(keywordCaptor.getValue().getNormalizedKeyword()).isEqualTo("그래픽카드");
      assertThat(response.getNormalizedKeyword()).isEqualTo("그래픽카드");
    }

    @Test
    @DisplayName("공백만 입력하면 SpecificationException이 발생한다")
    void create_blankKeyword_throwsException() {
      // Given
      HotDealKeywordCreate request = HotDealKeywordCreate.builder().keyword("   ").build();

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.create(request))
          .isInstanceOf(SpecificationException.class)
          .hasMessageContaining("공백만으로");
      then(keywordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("정규화 후 1자면 SpecificationException이 발생한다")
    void create_tooShortKeyword_throwsException() {
      // Given - 1자 키워드는 부분일치 특성상 오탐이 폭증한다
      HotDealKeywordCreate request = HotDealKeywordCreate.builder().keyword("아 ").build();

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.create(request))
          .isInstanceOf(SpecificationException.class)
          .hasMessageContaining("2자 이상");
      then(keywordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("정규화 결과가 같은 키워드는 중복으로 거부된다")
    void create_duplicateAfterNormalization_throwsException() {
      // Given - 기존에 "아이패드"가 등록된 상태에서 "아이 패드"를 시도
      HotDealKeywordCreate request = HotDealKeywordCreate.builder().keyword("아이 패드").build();
      given(keywordRepository.existsByNormalizedKeyword("아이패드")).willReturn(true);

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.create(request))
          .isInstanceOf(SpecificationException.class)
          .hasMessageContaining("이미 등록된 키워드");
      then(keywordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("대소문자만 다른 키워드는 중복으로 거부된다")
    void create_duplicateIgnoringCase_throwsException() {
      // Given
      HotDealKeywordCreate request = HotDealKeywordCreate.builder().keyword("IPAD").build();
      given(keywordRepository.existsByNormalizedKeyword("ipad")).willReturn(true);

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.create(request))
          .isInstanceOf(SpecificationException.class);
    }
  }

  @Nested
  @DisplayName("update")
  class Update {

    @Test
    @DisplayName("자기 자신은 중복 검사에서 제외된다")
    void update_sameKeyword_doesNotConflictWithItself() {
      // Given - 키워드는 그대로 두고 활성 토글만 끄는 경우
      HotDealKeyword existing = HotDealKeyword.create("아이패드", true);
      HotDealKeywordUpdate request = HotDealKeywordUpdate.builder()
          .keyword("아이패드")
          .enabled(false)
          .build();
      given(keywordRepository.findById(1L)).willReturn(Optional.of(existing));
      given(keywordRepository.existsByNormalizedKeywordAndIdNot("아이패드", 1L)).willReturn(false);

      // When
      HotDealKeywordResponse response = hotDealKeywordService.update(1L, request);

      // Then
      assertThat(response.isEnabled()).isFalse();
      assertThat(existing.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("다른 키워드와 정규화 값이 겹치면 거부된다")
    void update_conflictsWithOther_throwsException() {
      // Given
      HotDealKeyword existing = HotDealKeyword.create("닌텐도", true);
      HotDealKeywordUpdate request = HotDealKeywordUpdate.builder()
          .keyword("아이 패드")
          .enabled(true)
          .build();
      given(keywordRepository.findById(1L)).willReturn(Optional.of(existing));
      given(keywordRepository.existsByNormalizedKeywordAndIdNot("아이패드", 1L)).willReturn(true);

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.update(1L, request))
          .isInstanceOf(SpecificationException.class)
          .hasMessageContaining("이미 등록된 키워드");
    }

    @Test
    @DisplayName("존재하지 않는 ID면 DataNotFoundException이 발생한다")
    void update_notFound_throwsException() {
      // Given
      HotDealKeywordUpdate request = HotDealKeywordUpdate.builder()
          .keyword("아이패드")
          .enabled(true)
          .build();
      given(keywordRepository.findById(99L)).willReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.update(99L, request))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {

    @Test
    @DisplayName("삭제하면 DeleteResponse에 ID가 담긴다")
    void delete_existingId_returnsDeleteResponse() {
      // Given
      given(keywordRepository.existsById(1L)).willReturn(true);

      // When
      var response = hotDealKeywordService.delete(1L);

      // Then
      assertThat(response.getId()).isEqualTo(1L);
      then(keywordRepository).should().deleteById(1L);
    }

    @Test
    @DisplayName("존재하지 않는 ID면 DataNotFoundException이 발생한다")
    void delete_notFound_throwsException() {
      // Given
      given(keywordRepository.existsById(99L)).willReturn(false);

      // When / Then
      assertThatThrownBy(() -> hotDealKeywordService.delete(99L))
          .isInstanceOf(DataNotFoundException.class);
      then(keywordRepository).should(never()).deleteById(any());
    }
  }

  @Nested
  @DisplayName("getAllKeywords")
  class GetAllKeywords {

    @Test
    @DisplayName("비활성 키워드도 포함해 반환한다")
    void getAllKeywords_includesDisabled() {
      // Given
      given(keywordRepository.findAllByOrderByKeywordAsc()).willReturn(List.of(
          HotDealKeyword.create("닌텐도", true),
          HotDealKeyword.create("아이패드", false)));

      // When
      List<HotDealKeywordResponse> responses = hotDealKeywordService.getAllKeywords();

      // Then
      assertThat(responses).hasSize(2);
      assertThat(responses).extracting(HotDealKeywordResponse::isEnabled)
          .containsExactly(true, false);
    }
  }
}

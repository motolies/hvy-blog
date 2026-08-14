package kr.hvy.blog.modules.hotdeal.application.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HotDealKeywordMatcherTest {

  /** NBSP. 스크래핑한 HTML 제목에 &nbsp;로 흔히 섞여 들어온다. */
  private static final String NBSP = " ";

  /** 전각 공백. 한글 사이트 제목에 종종 쓰인다. */
  private static final String IDEOGRAPHIC_SPACE = "　";

  private static List<HotDealKeyword> keywords(String... values) {
    return Arrays.stream(values)
        .map(v -> HotDealKeyword.create(v, true))
        .toList();
  }

  @Nested
  @DisplayName("정규화 - 대소문자")
  class CaseInsensitive {

    @Test
    @DisplayName("대문자 키워드가 소문자 제목에 매칭된다")
    void match_upperCaseKeyword_matchesLowerCaseTitle() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("RTX"));

      // When
      KeywordMatchResult result = matcher.match("rtx 5070 최저가");

      // Then
      assertThat(result.matched()).isTrue();
      assertThat(result.matchedKeywords()).containsExactly("RTX");
    }

    @Test
    @DisplayName("소문자 키워드가 대문자 제목에 매칭된다")
    void match_lowerCaseKeyword_matchesUpperCaseTitle() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("rtx"));

      // When
      KeywordMatchResult result = matcher.match("RTX 5070 최저가");

      // Then
      assertThat(result.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("정규화 - 공백 제거")
  class WhitespaceRemoval {

    @Test
    @DisplayName("제목에 공백이 섞여 있어도 매칭된다")
    void match_titleWithSpaces_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("그래픽카드"));

      // When
      KeywordMatchResult result = matcher.match("[다나와] 그래픽 카드 특가");

      // Then
      assertThat(result.matched()).isTrue();
    }

    @Test
    @DisplayName("키워드에 공백이 섞여 있어도 매칭된다")
    void match_keywordWithSpaces_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("그래픽 카드"));

      // When
      KeywordMatchResult result = matcher.match("그래픽카드 특가");

      // Then
      assertThat(result.matched()).isTrue();
    }

    @Test
    @DisplayName("전각 공백(U+3000)도 제거되어 매칭된다")
    void match_ideographicSpace_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("아이패드"));

      // When
      KeywordMatchResult result = matcher.match("아이" + IDEOGRAPHIC_SPACE + "패드 프로 할인");

      // Then
      assertThat(result.matched()).isTrue();
    }

    @Test
    @DisplayName("NBSP(U+00A0)도 제거되어 매칭된다")
    void match_nonBreakingSpace_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("아이패드"));

      // When
      KeywordMatchResult result = matcher.match("아이" + NBSP + "패드 프로 할인");

      // Then
      assertThat(result.matched()).isTrue();
    }

    @Test
    @DisplayName("키워드 쪽에 NBSP가 있어도 매칭된다")
    void match_keywordWithNbsp_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("아이" + NBSP + "패드"));

      // When
      KeywordMatchResult result = matcher.match("아이패드 프로 할인");

      // Then
      assertThat(result.matched()).isTrue();
    }

    @Test
    @DisplayName("탭과 개행도 제거되어 매칭된다")
    void match_tabAndNewline_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("닌텐도"));

      // When
      KeywordMatchResult result = matcher.match("닌\t텐\n도 스위치");

      // Then
      assertThat(result.matched()).isTrue();
    }
  }

  @Nested
  @DisplayName("부분일치")
  class PartialMatch {

    @Test
    @DisplayName("제목 중간에 키워드가 있으면 매칭된다")
    void match_keywordInMiddle_matches() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("닌텐도"));

      // When
      KeywordMatchResult result = matcher.match("[G마켓] 닌텐도 스위치 할인");

      // Then
      assertThat(result.matched()).isTrue();
    }

    @Test
    @DisplayName("키워드가 없으면 매칭되지 않는다")
    void match_noKeywordInTitle_returnsNone() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("닌텐도"));

      // When
      KeywordMatchResult result = matcher.match("RTX 5070 최저가");

      // Then
      assertThat(result.matched()).isFalse();
      assertThat(result.matchedKeywords()).isEmpty();
    }
  }

  @Nested
  @DisplayName("다중 매칭")
  class MultipleMatch {

    @Test
    @DisplayName("여러 키워드가 걸리면 모두 반환한다")
    void match_multipleKeywords_returnsAll() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("닌텐도", "스위치"));

      // When
      KeywordMatchResult result = matcher.match("닌텐도 스위치 OLED 특가");

      // Then
      assertThat(result.matched()).isTrue();
      assertThat(result.matchedKeywords()).containsExactlyInAnyOrder("닌텐도", "스위치");
    }

    @Test
    @DisplayName("매칭 결과는 정규화 전 원문을 반환한다")
    void match_returnsOriginalKeyword() {
      // Given - Slack 메시지에는 사용자가 등록한 형태 그대로 표기해야 한다
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("아이 패드"));

      // When
      KeywordMatchResult result = matcher.match("아이패드 특가");

      // Then
      assertThat(result.matchedKeywords()).containsExactly("아이 패드");
    }
  }

  @Nested
  @DisplayName("경계 조건")
  class EdgeCases {

    @Test
    @DisplayName("키워드 목록이 비면 none을 반환한다")
    void match_emptyKeywords_returnsNone() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(List.of());

      // When
      KeywordMatchResult result = matcher.match("닌텐도 스위치");

      // Then
      assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("empty() 매처는 항상 none을 반환한다")
    void match_emptyMatcher_returnsNone() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.empty();

      // When
      KeywordMatchResult result = matcher.match("닌텐도 스위치");

      // Then
      assertThat(result.matched()).isFalse();
      assertThat(matcher.size()).isZero();
    }

    @Test
    @DisplayName("제목이 null이면 NPE 없이 none을 반환한다")
    void match_nullTitle_returnsNone() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("닌텐도"));

      // When
      KeywordMatchResult result = matcher.match(null);

      // Then
      assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("제목이 공백뿐이면 none을 반환한다")
    void match_blankTitle_returnsNone() {
      // Given
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("닌텐도"));

      // When
      KeywordMatchResult result = matcher.match("   ");

      // Then
      assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("공백만 있는 키워드는 매처 생성 시 제외된다")
    void of_blankKeyword_isExcluded() {
      // Given / When
      HotDealKeywordMatcher matcher = HotDealKeywordMatcher.of(keywords("   ", "닌텐도"));

      // Then
      assertThat(matcher.size()).isEqualTo(1);
      assertThat(matcher.match("닌텐도 스위치").matched()).isTrue();
    }

    @Test
    @DisplayName("KeywordMatchResult.none()의 matchedKeywords는 빈 리스트다")
    void none_hasEmptyKeywords() {
      // Given / When
      KeywordMatchResult result = KeywordMatchResult.none();

      // Then
      assertThat(result.matched()).isFalse();
      assertThat(result.matchedKeywords()).isEmpty();
    }
  }
}

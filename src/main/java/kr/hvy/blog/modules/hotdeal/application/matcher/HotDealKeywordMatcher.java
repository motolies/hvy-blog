package kr.hvy.blog.modules.hotdeal.application.matcher;

import java.util.List;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 핫딜 제목과 등록 키워드를 매칭한다.
 *
 * <p>Spring 빈이 아니라 스크래핑 실행 1회당 1개 생성되는 <b>불변 값 객체</b>다.
 * 생성 시점에 키워드를 모두 정규화해 두므로 딜 1건당 정규화는 제목 1회만 수행된다.
 *
 * <p>기존 임계값 조건({@code Specification<ScrapedDeal>})과 달리 별도 타입으로 둔 이유는,
 * Specification이 boolean만 반환해서 "어떤 키워드에 걸렸는지"를 꺼낼 수 없기 때문이다.
 *
 * <p>매칭 규칙: 제목 부분일치. 정규화는 소문자 변환 + 모든 공백 제거.
 */
public final class HotDealKeywordMatcher {

  private static final HotDealKeywordMatcher EMPTY = new HotDealKeywordMatcher(List.of());

  private final List<NormalizedKeyword> keywords;

  private HotDealKeywordMatcher(List<NormalizedKeyword> keywords) {
    this.keywords = keywords;
  }

  public static HotDealKeywordMatcher empty() {
    return EMPTY;
  }

  /**
   * 활성 키워드 엔티티 목록으로 매처를 생성한다.
   *
   * <p>DB에 저장된 normalizedKeyword를 그대로 쓰지 않고 원문으로 재정규화한다.
   * 수동 INSERT나 정규화 규칙 변경 이력이 있어도 매칭 기준이 항상 현재 코드와 일치하게 만든다.
   */
  public static HotDealKeywordMatcher of(List<HotDealKeyword> entities) {
    if (CollectionUtils.isEmpty(entities)) {
      return EMPTY;
    }
    List<NormalizedKeyword> normalized = entities.stream()
        .map(e -> new NormalizedKeyword(e.getKeyword(), HotDealKeyword.normalize(e.getKeyword())))
        .filter(k -> StringUtils.isNotBlank(k.normalized()))
        .toList();
    return normalized.isEmpty() ? EMPTY : new HotDealKeywordMatcher(normalized);
  }

  /**
   * 제목에 매칭된 키워드를 찾는다.
   */
  public KeywordMatchResult match(String title) {
    if (keywords.isEmpty() || StringUtils.isBlank(title)) {
      return KeywordMatchResult.none();
    }

    // 제목 정규화는 딜당 1회. 키워드마다 반복 정규화하지 않는다.
    String normalizedTitle = HotDealKeyword.normalize(title);

    List<String> matched = keywords.stream()
        .filter(k -> normalizedTitle.contains(k.normalized()))
        .map(NormalizedKeyword::origin)
        .toList();

    return KeywordMatchResult.of(matched);
  }

  public int size() {
    return keywords.size();
  }

  private record NormalizedKeyword(String origin, String normalized) {
  }
}

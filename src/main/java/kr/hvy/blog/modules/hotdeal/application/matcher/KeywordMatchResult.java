package kr.hvy.blog.modules.hotdeal.application.matcher;

import java.util.List;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 핫딜 제목 키워드 매칭 결과.
 *
 * <p>매칭 여부와 함께 매칭된 키워드 원문(정규화 전)을 담는다.
 * 원문을 담는 이유는 Slack 메시지에 "왜 알림이 왔는지"를 사람이 읽을 수 있게 표기하기 위함이다.
 */
public record KeywordMatchResult(boolean matched, List<String> matchedKeywords) {

  private static final KeywordMatchResult NONE = new KeywordMatchResult(false, List.of());

  public static KeywordMatchResult none() {
    return NONE;
  }

  public static KeywordMatchResult of(List<String> matchedKeywords) {
    return CollectionUtils.isEmpty(matchedKeywords)
        ? NONE
        : new KeywordMatchResult(true, List.copyOf(matchedKeywords));
  }
}

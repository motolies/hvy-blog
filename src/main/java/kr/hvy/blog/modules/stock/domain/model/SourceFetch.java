package kr.hvy.blog.modules.stock.domain.model;

import java.util.List;

/**
 * 외부(비 KIS) 원천 호출 결과. 행과 함께 HTTP 호출 수를 돌려주어 잡이 run 의 api_call_count 에 더할 수 있게 한다.
 */
public record SourceFetch<T>(List<T> rows, int httpCalls) {

  public static <T> SourceFetch<T> empty() {
    return new SourceFetch<>(List.of(), 0);
  }
}

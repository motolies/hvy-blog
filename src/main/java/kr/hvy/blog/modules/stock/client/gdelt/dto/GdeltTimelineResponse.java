package kr.hvy.blog.modules.stock.client.gdelt.dto;

import java.util.List;

/**
 * DOC 2.0 {@code mode=timelinevolraw|timelinetone&format=json} 응답.
 * {@code {"query_details":{…},"timeline":[{"series":"Article Count","data":[{"date":"20260912T000000Z","value":12,"norm":34567}]}]}}
 * (톤 모드는 {@code series="Average Tone"}, norm 없음). 알 수 없는 필드는 무시된다(Jackson 3 기본).
 */
public record GdeltTimelineResponse(List<Series> timeline) {

  public record Series(String series, List<Point> data) {
  }

  /**
   * @param date  버킷 시작 시각 UTC {@code yyyyMMdd'T'HHmmss'Z'}
   * @param value 기사 수 또는 평균 톤
   * @param norm  전체 모니터 기사 수 (volraw 에서만)
   */
  public record Point(String date, Double value, Double norm) {
  }
}

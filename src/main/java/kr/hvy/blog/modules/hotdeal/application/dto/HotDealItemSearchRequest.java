package kr.hvy.blog.modules.hotdeal.application.dto;

import java.time.LocalDateTime;
import kr.hvy.common.application.domain.dto.paging.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class HotDealItemSearchRequest extends PageRequest {

  private Long siteId;
  private String title;
  private Boolean notified;
  private String dealCategory;
  /**
   * 브라우저 로컬 일시 기준 수집 구간. 종료는 <b>포함</b>이며
   * {@code BrowserDateTimeConverter} 가 클라이언트 존을 적용해 UTC 반개구간으로 바꾼다.
   * 값은 ISO-8601 로컬 일시(예: {@code 2026-09-02T13:45:30}) — 존 표기가 붙으면 안 된다.
   */
  private LocalDateTime scrapedAtFrom;
  private LocalDateTime scrapedAtTo;
  private Integer minRecommendationCount;
  private Integer maxRecommendationCount;
  private Integer minViewCount;
  private Integer maxViewCount;
  private Integer minCommentCount;
  private Integer maxCommentCount;
}

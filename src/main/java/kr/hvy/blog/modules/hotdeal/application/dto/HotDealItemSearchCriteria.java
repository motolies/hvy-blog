package kr.hvy.blog.modules.hotdeal.application.dto;

import java.time.Instant;
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
public class HotDealItemSearchCriteria extends PageRequest {

  private Long siteId;
  private String title;
  private Boolean notified;
  private String dealCategory;
  private Instant scrapedAtFrom;
  private Instant scrapedAtToExclusive;
  private Integer minRecommendationCount;
  private Integer maxRecommendationCount;
  private Integer minViewCount;
  private Integer maxViewCount;
  private Integer minCommentCount;
  private Integer maxCommentCount;
}

package kr.hvy.blog.modules.hotdeal.application.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HotDealSiteUpdateRequest {

  private boolean enabled;

  @Min(0)
  private int minRecommendation;

  @Min(0)
  private int minViewCount;

  @Min(0)
  private int minCommentCount;
}

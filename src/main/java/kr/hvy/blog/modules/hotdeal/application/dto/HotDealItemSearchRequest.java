package kr.hvy.blog.modules.hotdeal.application.dto;

import java.time.LocalDate;
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
  private LocalDate scrapedAtFrom;
  private LocalDate scrapedAtTo;
}

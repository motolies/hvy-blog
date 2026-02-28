package kr.hvy.blog.modules.hotdeal.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ScrapedDeal {

  private String externalId;
  private String title;
  private String url;
  private String author;
  private int recommendationCount;
  private int unrecommendationCount;
  private int viewCount;
  private int commentCount;
  private String price;
  private String dealCategory;
  private String thumbnailUrl;
}

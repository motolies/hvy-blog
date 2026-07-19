package kr.hvy.blog.modules.hotdeal.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class HotDealItemSearchResponse {

  Long id;
  Long siteId;
  String siteName;
  String siteCode;
  String externalId;
  String title;
  String url;
  String author;
  int recommendationCount;
  int unrecommendationCount;
  int viewCount;
  int commentCount;
  String price;
  String dealCategory;
  String thumbnailUrl;
  boolean notified;
  Instant notifiedAt;
  Instant scrapedAt;
  Instant createdAt;
}

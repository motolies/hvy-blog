package kr.hvy.blog.modules.hotdeal.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class HotDealSiteResponse {

  Long id;
  String siteCode;
  String siteName;
  String siteUrl;
  String boardUrl;
  boolean enabled;
  boolean requiresLogin;
  int minRecommendation;
  int minViewCount;
  int minCommentCount;
  Instant createdAt;
  Instant updatedAt;
}

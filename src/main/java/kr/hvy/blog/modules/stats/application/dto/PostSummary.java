package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 포스트 상태별 집계. */
@Value
@Builder
@Jacksonized
public class PostSummary {

  long totalPosts;
  long publishedPosts;
  long temporaryPosts;
  long privatePosts;
  long totalViews;
  /** 실제로 글이 달린 카테고리 수 — 전체 카테고리 수(TaxonomySummary.totalCategories)와 다르다. */
  long usedCategories;
  Instant lastPublishedAt;
}

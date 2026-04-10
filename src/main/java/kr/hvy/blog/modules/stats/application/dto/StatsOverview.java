package kr.hvy.blog.modules.stats.application.dto;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class StatsOverview {

  long totalPosts;
  long totalViews;
  long todayViews;
  long totalCategories;
  long totalTags;
  List<DailyViewCount> viewTrend;
  List<PopularPost> popularPosts;
  List<CategoryDistribution> categoryDistribution;
  List<TagDistribution> tagDistribution;
}

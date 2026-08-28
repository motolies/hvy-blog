package kr.hvy.blog.modules.stats.application.dto;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** GET /api/stats/admin/summary — 콘텐츠 현황(로그 테이블을 조회하지 않는다). */
@Value
@Builder
@Jacksonized
public class StatsSummary {

  PostSummary posts;
  TaxonomySummary taxonomy;
  List<CategoryDistribution> categoryDistribution;
  List<TagDistribution> tagDistribution;
  List<MonthlyPostCount> monthlyPostCounts;
}

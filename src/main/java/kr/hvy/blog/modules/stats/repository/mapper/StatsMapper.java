package kr.hvy.blog.modules.stats.repository.mapper;

import java.time.Instant;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.CategoryDistribution;
import kr.hvy.blog.modules.stats.application.dto.DailyViewCount;
import kr.hvy.blog.modules.stats.application.dto.PopularPost;
import kr.hvy.blog.modules.stats.application.dto.TagDistribution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StatsMapper {

  long countPublishedPosts();

  long sumViewCounts();

  long countTodayViews(@Param("todayStart") Instant todayStart,
      @Param("tomorrowStart") Instant tomorrowStart);

  long countCategories();

  long countTags();

  // zoneId: 일별 그룹핑의 날짜 경계 기준 타임존 (클라이언트 존)
  List<DailyViewCount> findDailyViewCounts(@Param("from") Instant from,
      @Param("to") Instant to, @Param("zoneId") String zoneId);

  List<PopularPost> findPopularPosts(@Param("limit") int limit);

  List<CategoryDistribution> findCategoryDistribution();

  List<TagDistribution> findTagDistribution(@Param("limit") int limit);
}

package kr.hvy.blog.modules.stats.repository.mapper;

import java.time.LocalDateTime;
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

  long countTodayViews(@Param("todayStart") LocalDateTime todayStart,
      @Param("tomorrowStart") LocalDateTime tomorrowStart);

  long countCategories();

  long countTags();

  List<DailyViewCount> findDailyViewCounts(@Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);

  List<PopularPost> findPopularPosts(@Param("limit") int limit);

  List<CategoryDistribution> findCategoryDistribution();

  List<TagDistribution> findTagDistribution(@Param("limit") int limit);
}

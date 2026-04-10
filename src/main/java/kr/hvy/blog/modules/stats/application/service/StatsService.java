package kr.hvy.blog.modules.stats.application.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.CategoryDistribution;
import kr.hvy.blog.modules.stats.application.dto.DailyViewCount;
import kr.hvy.blog.modules.stats.application.dto.PopularPost;
import kr.hvy.blog.modules.stats.application.dto.StatsOverview;
import kr.hvy.blog.modules.stats.application.dto.TagDistribution;
import kr.hvy.blog.modules.stats.repository.mapper.StatsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

  private final StatsMapper statsMapper;

  public StatsOverview getOverview(int trendDays) {
    LocalDate today = LocalDate.now();
    LocalDateTime todayStart = today.atStartOfDay();
    LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

    LocalDateTime trendFrom = today.minusDays(trendDays - 1).atStartOfDay();
    LocalDateTime trendTo = tomorrowStart;

    long totalPosts = statsMapper.countPublishedPosts();
    long totalViews = statsMapper.sumViewCounts();
    long todayViews = statsMapper.countTodayViews(todayStart, tomorrowStart);
    long totalCategories = statsMapper.countCategories();
    long totalTags = statsMapper.countTags();

    List<DailyViewCount> viewTrend = statsMapper.findDailyViewCounts(trendFrom, trendTo);
    List<PopularPost> popularPosts = statsMapper.findPopularPosts(10);
    List<CategoryDistribution> categoryDistribution = statsMapper.findCategoryDistribution();
    List<TagDistribution> tagDistribution = statsMapper.findTagDistribution(20);

    return StatsOverview.builder()
        .totalPosts(totalPosts)
        .totalViews(totalViews)
        .todayViews(todayViews)
        .totalCategories(totalCategories)
        .totalTags(totalTags)
        .viewTrend(viewTrend)
        .popularPosts(popularPosts)
        .categoryDistribution(categoryDistribution)
        .tagDistribution(tagDistribution)
        .build();
  }
}

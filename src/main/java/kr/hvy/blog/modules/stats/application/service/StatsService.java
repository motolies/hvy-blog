package kr.hvy.blog.modules.stats.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.CategoryDistribution;
import kr.hvy.blog.modules.stats.application.dto.DailyViewCount;
import kr.hvy.blog.modules.stats.application.dto.PopularPost;
import kr.hvy.blog.modules.stats.application.dto.StatsOverview;
import kr.hvy.blog.modules.stats.application.dto.TagDistribution;
import kr.hvy.blog.modules.stats.repository.mapper.StatsMapper;
import kr.hvy.common.core.time.ClientTimeZoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

  private final StatsMapper statsMapper;
  private final ClientTimeZoneResolver clientTimeZoneResolver;

  /**
   * '오늘'과 일별 집계 경계를 클라이언트 타임존(X-Client-Timezone 헤더) 기준으로 계산한다.
   * 헤더가 없으면 UTC 폴백 — 기존 UTC 자정 경계와 동일하게 동작.
   */
  public StatsOverview getOverview(int trendDays) {
    ZoneId zone = clientTimeZoneResolver.resolve().zoneId();
    LocalDate today = LocalDate.now(zone);
    Instant todayStart = today.atStartOfDay(zone).toInstant();
    Instant tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant();

    Instant trendFrom = today.minusDays(trendDays - 1).atStartOfDay(zone).toInstant();
    Instant trendTo = tomorrowStart;

    long totalPosts = statsMapper.countPublishedPosts();
    long totalViews = statsMapper.sumViewCounts();
    long todayViews = statsMapper.countTodayViews(todayStart, tomorrowStart);
    long totalCategories = statsMapper.countCategories();
    long totalTags = statsMapper.countTags();

    List<DailyViewCount> viewTrend = statsMapper.findDailyViewCounts(trendFrom, trendTo, zone.getId());
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

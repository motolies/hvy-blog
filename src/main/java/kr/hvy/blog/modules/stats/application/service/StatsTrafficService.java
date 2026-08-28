package kr.hvy.blog.modules.stats.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.stats.application.dto.DailyTraffic;
import kr.hvy.blog.modules.stats.application.dto.TrafficStats;
import kr.hvy.blog.modules.stats.repository.mapper.StatsLogMapper;
import kr.hvy.blog.modules.stats.repository.mapper.StatsMapper;
import kr.hvy.common.core.time.ClientTimeZoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트래픽 집계.
 * <p>
 * 데이터 출처가 둘이다 — 누적 조회수는 {@code tb_post.view_count}, 시계열·방문자는
 * beacon 이 남긴 {@code tb_system_log} 행이다. beacon 하나가 카운터와 시계열을 동시에 만든다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsTrafficService {

  private static final int POPULAR_LIMIT = 5;
  private static final int TOP_URI_LIMIT = 5;

  private final StatsMapper statsMapper;
  private final StatsLogMapper statsLogMapper;
  private final ClientTimeZoneResolver clientTimeZoneResolver;

  public TrafficStats getTraffic(int days) {
    ZoneId zone = clientTimeZoneResolver.resolve().zoneId();
    LocalDate today = LocalDate.now(zone);
    LocalDate fromDate = today.minusDays(Math.max(1, days) - 1L);

    Instant from = fromDate.atStartOfDay(zone).toInstant();
    Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();

    List<DailyTraffic> trend = fillGaps(
        statsLogMapper.findDailyTraffic(from, to, zone.getId()), fromDate, today);

    DailyTraffic todayRow = dayOf(trend, today);
    DailyTraffic yesterdayRow = dayOf(trend, today.minusDays(1));

    return TrafficStats.builder()
        .timeZone(zone.getId())
        .fromDate(fromDate)
        .toDate(today)
        .dailyTrend(trend)
        .todayVisitors(todayRow.getVisitorCount())
        .yesterdayVisitors(yesterdayRow.getVisitorCount())
        .visitorDeltaPercent(deltaPercent(todayRow.getVisitorCount(), yesterdayRow.getVisitorCount()))
        .todayRequests(todayRow.getRequestCount())
        .yesterdayRequests(yesterdayRow.getRequestCount())
        .requestDeltaPercent(deltaPercent(todayRow.getRequestCount(), yesterdayRow.getRequestCount()))
        .todayPostViews(todayRow.getPostViewCount())
        .yesterdayPostViews(yesterdayRow.getPostViewCount())
        .postViewDeltaPercent(deltaPercent(todayRow.getPostViewCount(), yesterdayRow.getPostViewCount()))
        .popularPostsAllTime(statsMapper.findPopularPosts(POPULAR_LIMIT))
        .popularPostsRecent(statsLogMapper.findRecentPopularPosts(from, to, POPULAR_LIMIT))
        .topRequestUris(statsLogMapper.findTopRequestUris(from, to, TOP_URI_LIMIT))
        .collectionStartedAt(statsLogMapper.findViewCollectionStartedAt())
        .build();
  }

  /**
   * 조회가 없던 날을 0으로 채운다.
   * <p>
   * 채우지 않으면 차트의 x축이 조용히 압축되어 <b>추이가 거짓말을 한다</b> —
   * 트래픽이 없던 구간이 그래프에서 사라지므로 실제보다 고르게 보인다.
   */
  private List<DailyTraffic> fillGaps(List<DailyTraffic> rows, LocalDate from, LocalDate to) {
    Map<LocalDate, DailyTraffic> byDate = rows.stream()
        .collect(Collectors.toMap(DailyTraffic::getDate, Function.identity(),
            (a, b) -> a, LinkedHashMap::new));

    List<DailyTraffic> filled = new ArrayList<>();
    for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
      filled.add(byDate.getOrDefault(date, emptyDay(date)));
    }
    return filled;
  }

  private DailyTraffic dayOf(List<DailyTraffic> trend, LocalDate date) {
    return trend.stream()
        .filter(row -> row.getDate().equals(date))
        .findFirst()
        .orElseGet(() -> emptyDay(date));
  }

  private DailyTraffic emptyDay(LocalDate date) {
    return DailyTraffic.builder()
        .date(date).requestCount(0).visitorCount(0).postViewCount(0).build();
  }

  /**
   * 증감률. 기준이 0이면 비율을 정의할 수 없으므로 null 을 반환한다 —
   * 여기서 0이나 무한대를 내보내면 화면이 "+∞%" 같은 헛소리를 하게 된다.
   */
  private Double deltaPercent(long current, long previous) {
    if (previous == 0) {
      return null;
    }
    return ((double) (current - previous) / previous) * 100.0;
  }
}

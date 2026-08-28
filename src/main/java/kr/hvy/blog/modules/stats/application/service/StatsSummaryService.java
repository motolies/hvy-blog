package kr.hvy.blog.modules.stats.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import kr.hvy.blog.modules.stats.application.dto.StatsSummary;
import kr.hvy.blog.modules.stats.repository.mapper.StatsMapper;
import kr.hvy.common.core.time.ClientTimeZoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 콘텐츠 현황 집계. 로그 테이블을 건드리지 않으므로 가볍다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsSummaryService {

  private static final int TAG_DISTRIBUTION_LIMIT = 20;

  private final StatsMapper statsMapper;
  private final ClientTimeZoneResolver clientTimeZoneResolver;

  public StatsSummary getSummary(int monthlyMonths) {
    ZoneId zone = clientTimeZoneResolver.resolve().zoneId();
    Instant monthlyFrom = LocalDate.now(zone)
        .withDayOfMonth(1)
        .minusMonths(Math.max(0, monthlyMonths - 1L))
        .atStartOfDay(zone)
        .toInstant();

    return StatsSummary.builder()
        .posts(statsMapper.findPostSummary())
        .taxonomy(statsMapper.findTaxonomySummary())
        .categoryDistribution(statsMapper.findCategoryDistribution())
        .tagDistribution(statsMapper.findTagDistribution(TAG_DISTRIBUTION_LIMIT))
        .monthlyPostCounts(statsMapper.findMonthlyPostCounts(monthlyFrom, zone.getId()))
        .build();
  }
}

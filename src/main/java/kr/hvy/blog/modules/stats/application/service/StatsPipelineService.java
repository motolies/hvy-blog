package kr.hvy.blog.modules.stats.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kr.hvy.blog.modules.hotdeal.repository.HotDealKeywordRepository;
import kr.hvy.blog.modules.stats.application.dto.HotDealSiteStat;
import kr.hvy.blog.modules.stats.application.dto.PipelineStats;
import kr.hvy.blog.modules.stats.repository.mapper.StatsPipelineMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사이드 파이프라인 집계 — 핫딜 / 메모 / Jira. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsPipelineService {

  private final StatsPipelineMapper statsPipelineMapper;
  private final HotDealKeywordRepository hotDealKeywordRepository;

  public PipelineStats getPipeline(int hours) {
    int windowHours = Math.max(1, hours);
    Instant now = Instant.now();
    Instant from = now.minus(Duration.ofHours(windowHours));

    List<HotDealSiteStat> sites = statsPipelineMapper.findHotDealSiteStats(from, now);

    // 합계는 리스트를 리듀스해서 만든다 — 쿼리를 하나 아끼고, 사이트별 분해가 정본으로 남는다
    long scraped = sites.stream().mapToLong(HotDealSiteStat::getScrapedCount).sum();
    long notified = sites.stream().mapToLong(HotDealSiteStat::getNotifiedCount).sum();
    Instant lastScrapedAt = sites.stream()
        .map(HotDealSiteStat::getLastScrapedAt)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);

    return PipelineStats.builder()
        .windowFrom(from)
        .windowTo(now)
        .windowHours(windowHours)
        .hotDealSites(sites)
        .hotDealScrapedCount(scraped)
        .hotDealNotifiedCount(notified)
        .hotDealNotifiedRatio(scraped == 0 ? null : ((double) notified / scraped) * 100.0)
        .hotDealLastScrapedAt(lastScrapedAt)
        .enabledKeywordCount(hotDealKeywordRepository.countByEnabledTrue())
        .memo(statsPipelineMapper.findMemoStat(from))
        .jira(statsPipelineMapper.findJiraStat())
        .build();
  }
}

package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** GET /api/stats/admin/pipeline — 사이드 파이프라인 위젯 전체. */
@Value
@Builder
@Jacksonized
public class PipelineStats {

  Instant windowFrom;
  Instant windowTo;
  int windowHours;

  List<HotDealSiteStat> hotDealSites;
  long hotDealScrapedCount;
  long hotDealNotifiedCount;
  Double hotDealNotifiedRatio;
  Instant hotDealLastScrapedAt;
  long enabledKeywordCount;

  MemoStat memo;
  JiraStat jira;
}

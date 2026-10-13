package kr.hvy.blog.modules.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.*;
import kr.hvy.blog.modules.advisor.domain.model.*;

/** Public analysis fields, independent of internal prompt and model diagnostics. */
public record McpAdviceHeader(
    Long adviceId,
    long runId,
    LocalDate baseDate,
    AdviceKind adviceKind,
    AdviceVariant variant,
    int horizonDays,
    MarketRegimeCode regimeCode,
    DirectionCall kospiDir,
    DirectionCall kosdaqDir,
    Double pUp,
    String regimeRationale,
    List<SectorCall> leadingSectors,
    String summary,
    MarketTrendCode trendKospi,
    MarketTrendCode trendKosdaq,
    List<MarketTrend> trends,
    List<TrendOutlook> outlooks,
    Map<String, Object> dataAsOf,
    LocalDate entryDate,
    LocalDate exitDate,
    List<String> newsIds,
    String model,
    Long weightSetId,
    List<Long> activeLessonIds,
    DataQuality dataQuality,
    Map<String, Object> guard,
    Instant publishedAt,
    Instant createdAt,
    Long parentAdviceId,
    Map<String, Object> diffJson,
    MarketRegime regime) {
  static McpAdviceHeader from(AdviceHeader h) {
    return new McpAdviceHeader(
        h.adviceId(),
        h.runId(),
        h.baseDate(),
        h.adviceKind(),
        h.variant(),
        h.horizonDays(),
        h.regimeCode(),
        h.kospiDir(),
        h.kosdaqDir(),
        h.pUp(),
        h.regimeRationale(),
        h.leadingSectors(),
        h.summary(),
        h.trendKospi(),
        h.trendKosdaq(),
        h.trends(),
        h.outlooks(),
        h.dataAsOf(),
        h.entryDate(),
        h.exitDate(),
        h.newsIds(),
        h.model(),
        h.weightSetId(),
        h.activeLessonIds(),
        h.dataQuality(),
        h.guard(),
        h.publishedAt(),
        h.createdAt(),
        h.parentAdviceId(),
        h.diffJson(),
        h.regime());
  }
}

package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import lombok.Builder;

/**
 * 판단 헤더 (tb_advisor_advice). adviceId 는 저장 후 채워진다.
 * <p>
 * advice-v2: 규칙 추세(trendKospi·trendKosdaq·trends), LLM 추세 전망(outlooks), 관측 기준일(dataAsOf), 적용 구간(entryDate·exitDate)이 더해졌다.
 */
@Builder(toBuilder = true)
public record AdviceHeader(
    Long adviceId,
    long runId,
    LocalDate baseDate,
    String adviceKind,
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
    /** 프롬프트에 실린 헤드라인 id (advice-v4, 뉴스 없으면 빈 목록). LLM_NONEWS 게이트·재현성 스키마 재구성에 쓴다 */
    List<String> newsIds,
    String promptVersion,
    String model,
    String systemFingerprint,
    Long weightSetId,
    List<Long> activeLessonIds,
    DataQuality dataQuality,
    Map<String, Object> guard,
    Instant publishedAt,
    Instant createdAt) {

  public static final String KIND_DAILY = "DAILY";

  /**
   * 지수 코드의 규칙 추세 (0001 → trendKospi, 1001 → trendKosdaq).
   */
  public MarketTrendCode trendOf(String indexCode) {
    return "0001".equals(indexCode) ? trendKospi : "1001".equals(indexCode) ? trendKosdaq : null;
  }

  /**
   * 지수 코드의 추세 전망 (없으면 null).
   */
  public TrendOutlook outlookOf(String indexCode) {
    if (outlooks == null) {
      return null;
    }
    return outlooks.stream().filter(o -> indexCode.equals(o.indexCode())).findFirst().orElse(null);
  }
}

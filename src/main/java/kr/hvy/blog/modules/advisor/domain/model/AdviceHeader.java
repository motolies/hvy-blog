package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import lombok.Builder;

/**
 * 판단 헤더 (tb_advisor_advice). adviceId 는 저장 후 채워진다.
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
}

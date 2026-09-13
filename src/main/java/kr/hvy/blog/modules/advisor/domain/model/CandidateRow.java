package kr.hvy.blog.modules.advisor.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 정량 스크리닝 후보 1개의 동결 스냅샷 (tb_advisor_candidate).
 */
@Builder(toBuilder = true)
public record CandidateRow(
    String ticker,
    int quantRank,
    double quantScore,
    String stockName,
    String marketType,
    String benchIndexCode,
    String sectorCode,
    String sectorName,
    Map<String, SignalValue> signals,
    Map<String, Object> features,
    List<Long> appliedLessonIds,
    BigDecimal refRawClose,
    Double refAdjClose) {
}

package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import lombok.Builder;

/**
 * 장중 점검 1행 (tb_advisor_intraday_check).
 */
@Builder(toBuilder = true)
public record IntradayCheckRow(
    Long checkId,
    long adviceId,
    Long runId,
    Instant checkedAt,
    Map<String, Object> indexJson,
    List<Map<String, Object>> pickJson,
    Double agreementRatio,
    IntradayVerdict verdict,
    String comment) {
}

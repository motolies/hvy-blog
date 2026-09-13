package kr.hvy.blog.modules.advisor.domain.model;

import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import lombok.Builder;

/**
 * 픽 1개 (tb_advisor_pick). 반드시 같은 advice 의 후보여야 한다.
 */
@Builder(toBuilder = true)
public record PickRow(
    String ticker,
    int pickRank,
    PickDirection direction,
    double conviction,
    String thesis,
    String riskNote,
    List<CitedFeature> cited) {
}

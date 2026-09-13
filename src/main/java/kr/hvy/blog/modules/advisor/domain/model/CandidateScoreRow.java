package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import lombok.Builder;

/**
 * 후보 종목 채점 1행 (tb_advisor_candidate_score).
 */
@Builder(toBuilder = true)
public record CandidateScoreRow(
    long adviceId,
    String ticker,
    int horizonDays,
    ScoreStage stage,
    ScoreStatus status,
    LocalDate entryDate,
    Double entryPrice,
    LocalDate exitDate,
    Double exitPrice,
    Double ret,
    double dividendRet,
    Double benchRet,
    Double excessRet,
    Double costAdjExcess) {
}

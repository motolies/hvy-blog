package kr.hvy.blog.modules.advisor.domain.model;

import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import lombok.Builder;

/**
 * 국면·섹터 콜 채점 1행 (tb_advisor_call_score).
 */
@Builder(toBuilder = true)
public record CallScoreRow(
    long adviceId,
    CallSubject subjectType,
    String subjectCode,
    int horizonDays,
    ScoreStage stage,
    ScoreStatus status,
    String predicted,
    Double pUp,
    Double baseValue,
    Double exitValue,
    Double actualRet,
    Double benchRet,
    Double band,
    String actualDir,
    Boolean hit,
    Double brier,
    /** TREND: 라벨 전환일 | TREND_INV: 무효화 조건 최초 충족일 (없으면 null) */
    java.time.LocalDate eventDate) {
}

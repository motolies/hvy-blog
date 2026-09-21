package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import lombok.Builder;

/**
 * 12:00 픽 노트 1행 (tb_advisor_pick_note, 컬럼 1:1). 12:00 관측값은 이 테이블에만 산다 — 픽·후보·채점·IC·교훈 SQL 은 참조하지 않는다(룩어헤드 경계).
 * <ul>
 *   <li>changeRate·benchRate 는 KIS 전일 대비율 그대로 %(예: 1.2), gapRate·sinceOpenRate·excessRate·finalExcess 는 소수 비율(예: 0.012)</li>
 *   <li>tags: {signals[], sector, regime, excessBasis(OPEN|PREV_CLOSE), offHours, benchCode, benchSinceOpen, secCons(1|0|null — AdvicePromptBuilder.secCons 와 같은 정의)}</li>
 * </ul>
 */
@Builder(toBuilder = true)
public record PickNoteRow(
    Long noteId,
    long adviceId,
    long checkId,
    String ticker,
    LocalDate baseDate,
    Instant notedAt,
    PickDirection direction,
    double conviction,
    Double openPrice,
    Double currentPrice,
    Double changeRate,
    Double gapRate,
    Double sinceOpenRate,
    Double benchRate,
    Double excessRate,
    Double zScore,
    PickNoteClass noteClass,
    String deviation,
    String why,
    String hypothesis,
    Map<String, Object> tags,
    PickNoteStatus status,
    Double finalExcess,
    Instant finalizedAt,
    String model,
    Long runId,
    Instant createdAt) {
}

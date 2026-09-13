package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import lombok.Builder;

/**
 * 아침 점검 1행 (tb_advisor_morning_check, advice 당 1행). 원 판단은 건드리지 않고 별도 메모로 남긴다 — 채점 일관성.
 *
 * @param usDate     반영한 미국 세션의 현지 거래일 (정상이면 판단 기준일과 같다)
 * @param gapKospi   예상 갭 = β(KOSPI:주 심볼) × 미국 1일 수익률
 * @param gapKosdaq  예상 갭 (KOSDAQ)
 * @param verdict    지수별 판정 중 가장 심각한 것
 * @param detailJson 심볼별 수익률·지수별 β·임계·판정 등 상세
 */
@Builder(toBuilder = true)
public record MorningCheckRow(
    Long checkId,
    long adviceId,
    Long runId,
    LocalDate baseDate,
    LocalDate usDate,
    Double gapKospi,
    Double gapKosdaq,
    MorningVerdict verdict,
    Map<String, Object> detailJson,
    Instant publishedAt,
    Instant createdAt) {
}

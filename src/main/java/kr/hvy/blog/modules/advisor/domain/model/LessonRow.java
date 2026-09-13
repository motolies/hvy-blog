package kr.hvy.blog.modules.advisor.domain.model;

import java.time.Instant;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.LessonScope;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import lombok.Builder;

/**
 * 교훈 1행 (tb_advisor_lesson).
 */
@Builder(toBuilder = true)
public record LessonRow(
    Long lessonId,
    LessonStatus status,
    LessonScope scope,
    Map<String, Object> condition,
    String observation,
    Map<String, Object> evidence,
    String rule,
    String lessonText,
    int appliedCount,
    Integer postNApplied,
    Double postExcessApplied,
    Integer postNNotApplied,
    Double postExcessNotApplied,
    Instant activatedAt,
    Instant retiredAt,
    String retiredReason,
    String model,
    Long runId,
    Instant createdAt) {
}
